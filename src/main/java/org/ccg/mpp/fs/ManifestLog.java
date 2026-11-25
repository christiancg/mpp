package org.ccg.mpp.fs;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.FileEntry;
import org.ccg.mpp.fs.io.ChunkedChannelWrites;
import org.ccg.mpp.log.Logger;

final class ManifestLog {
    private static final Logger logger = Logger.logFor(ManifestLog.class);
    private static final String SEPARATOR = Globals.ID_SEPARATOR;
    private static final String INCARNATION = "I";
    private static final String FILE = "F";
    private static final String DIRECTORY = "D";
    private static final String TOMBSTONE = "X";
    private static final String PENDING = "P";
    private static final String MOVE = "M";
    private static final String BATCH = "B";
    private static final String BATCH_END = "E";

    private ManifestLog() {
    }

    static String incarnation(long incarnation) {
        return INCARNATION + SEPARATOR + incarnation;
    }

    static String file(String path, long version, long size) {
        return FILE + SEPARATOR + path + SEPARATOR + version + SEPARATOR + size;
    }

    static String directory(String path, long version) {
        return DIRECTORY + SEPARATOR + path + SEPARATOR + version;
    }

    static String tombstone(String path, long version) {
        return TOMBSTONE + SEPARATOR + path + SEPARATOR + version;
    }

    static String pending(String path, long version, long size, String tmpName) {
        return PENDING + SEPARATOR + path + SEPARATOR + version + SEPARATOR + size + SEPARATOR + tmpName;
    }

    static String move(String from, String to) {
        return MOVE + SEPARATOR + from + SEPARATOR + to;
    }

    static String entry(String path, FileEntry entry) {
        return entry.isDirectory() ? directory(path, entry.version()) : file(path, entry.version(), entry.size());
    }

    static boolean load(Path file, FileManifest manifest) throws IOException {
        if (!Files.exists(file)) {
            return false;
        }
        final var bytes = Files.readAllBytes(file);
        final var text = new String(bytes, StandardCharsets.UTF_8);
        final var lines = new ArrayList<>(List.of(text.split(Globals.NEWLINE, -1)));
        var torn = !lines.getLast().isEmpty();
        lines.removeLast();
        var index = 0;
        while (index < lines.size()) {
            final var line = lines.get(index);
            if (line.startsWith(BATCH + SEPARATOR)) {
                final var end = batchEnd(file, lines, index);
                if (end < 0) {
                    torn = true;
                    break;
                }
                for (var member = index + 1; member < end; member++) {
                    apply(file, member, lines.get(member), manifest);
                }
                index = end + 1;
                continue;
            }
            if (rejects(line, manifest)) {
                requireTail(file, index, index + 1, lines.size());
                torn = true;
                break;
            }
            index++;
        }
        manifest.resetLogLines(lines.size());
        if (torn) {
            logger.warning("The file manifest " + file + " ended with an incomplete record, left by a write that"
                    + " never committed; it is dropped and the manifest rewritten");
        }
        return torn;
    }

    private static int batchEnd(Path file, List<String> lines, int start) throws IOException {
        final int count;
        try {
            count = Integer.parseInt(lines.get(start).substring(2));
        } catch (NumberFormatException e) {
            requireTail(file, start, start + 1, lines.size());
            return -1;
        }
        final var end = start + count + 1;
        if (count < 0 || end >= lines.size()) {
            return -1;
        }
        final var closing = lines.get(end);
        if (closing.startsWith(BATCH_END + SEPARATOR)
                && Long.toHexString(crcOf(lines.subList(start + 1, end))).equals(closing.substring(2))) {
            return end;
        }
        requireTail(file, start, end + 1, lines.size());
        return -1;
    }

    private static void requireTail(Path file, int index, int consumedUpTo, int total) throws IOException {
        if (consumedUpTo < total) {
            throw new IOException("The file manifest " + file + " is damaged at line " + (index + 1)
                    + ", before its last record; its project's files cannot be read until it is repaired");
        }
    }

    private static void apply(Path file, int index, String line, FileManifest manifest) throws IOException {
        if (rejects(line, manifest)) {
            throw new IOException("The file manifest " + file + " holds an unreadable record at line " + (index + 1));
        }
    }

    private static boolean rejects(String line, FileManifest manifest) {
        final var fields = line.split(SEPARATOR, -1);
        try {
            switch (fields[0]) {
                case INCARNATION -> manifest.setIncarnation(Long.parseLong(fields[1]));
                case FILE ->
                    manifest.put(fields[1], FileEntry.file(Long.parseLong(fields[2]), Long.parseLong(fields[3])));
                case DIRECTORY -> manifest.put(fields[1], FileEntry.directory(Long.parseLong(fields[2])));
                case TOMBSTONE -> manifest.bury(fields[1], Long.parseLong(fields[2]));
                case PENDING -> manifest.putPending(fields[1],
                        FileEntry.file(Long.parseLong(fields[2]), Long.parseLong(fields[3])), fields[4]);
                case MOVE -> manifest.noteMove(fields[1], fields[2]);
                default -> {
                    return true;
                }
            }
            return !fields[0].equals(INCARNATION) && !fields[1].startsWith("/");
        } catch (RuntimeException malformed) {
            return true;
        }
    }

    static void append(Path file, List<String> records) throws IOException {
        final var text = new StringBuilder();
        if (records.size() == 1) {
            text.append(records.getFirst()).append(Globals.NEWLINE);
        } else {
            text.append(BATCH).append(SEPARATOR).append(records.size()).append(Globals.NEWLINE);
            for (final var record : records) {
                text.append(record).append(Globals.NEWLINE);
            }
            text.append(BATCH_END).append(SEPARATOR).append(Long.toHexString(crcOf(records))).append(Globals.NEWLINE);
        }
        OwnerOnly.ensureOwnerOnlyFile(file);
        final var bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        try (var channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            final var lengthBefore = channel.size();
            try {
                ChunkedChannelWrites.writeAt(channel, lengthBefore, bytes, 0, bytes.length);
                SyncedWrites.content(channel, file);
            } catch (IOException | RuntimeException e) {
                cutBack(channel, lengthBefore, e);
                throw e;
            }
        }
    }

    private static void cutBack(FileChannel channel, long length, Exception cause) {
        try {
            channel.truncate(length);
        } catch (IOException e) {
            cause.addSuppressed(e);
        }
    }

    static void commit(ProjectFileTree tree, FileManifest manifest, List<String> records) throws IOException {
        final var logged = new ArrayList<String>(records.size() + 1);
        if (!Files.exists(tree.manifest())) {
            logged.add(incarnation(manifest.incarnation()));
        }
        logged.addAll(records);
        append(tree.manifest(), logged);
        manifest.countLogLines(logged.size() == 1 ? 1 : logged.size() + 2);
    }

    static void rewrite(Path file, List<String> records) throws IOException {
        FileLocks.rewriteFileAtomically(file, records);
    }

    private static long crcOf(List<String> records) {
        final var crc = new CRC32();
        for (final var record : records) {
            crc.update(record.getBytes(StandardCharsets.UTF_8));
            crc.update('\n');
        }
        return crc.getValue();
    }
}
