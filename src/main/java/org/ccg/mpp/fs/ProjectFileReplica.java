package org.ccg.mpp.fs;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import org.ccg.mpp.data.FileEntry;
import org.ccg.mpp.data.FileKind;
import org.ccg.mpp.data.ProjectFilePath;
import org.ccg.mpp.ex.FileApplyPendingException;
import org.ccg.mpp.fs.io.ChunkedChannelWrites;

public final class ProjectFileReplica {
    private static final AtomicLong TEMP_NAMES = new AtomicLong();
    private final ProjectFileStore store;
    private final String projectName;
    private final FileManifest manifest;
    private final ProjectFileTree tree;
    private final long waitMillis;

    public record Described(String path, FileKind kind, long version, long size, boolean tombstone) {
    }

    ProjectFileReplica(ProjectFileStore store, String projectName, FileManifest manifest, ProjectFileTree tree,
            long waitMillis) {
        this.store = store;
        this.projectName = projectName;
        this.manifest = manifest;
        this.tree = tree;
        this.waitMillis = waitMillis;
    }

    public long localVersion(String path) throws IOException {
        final var lock = ManifestLocks.read(manifest, projectName, waitMillis);
        try {
            return versionOf(path);
        } finally {
            lock.unlock();
        }
    }

    private long versionOf(String path) {
        final var entry = manifest.get(path);
        final var tombstone = manifest.tombstone(path);
        return Math.max(entry == null ? 0L : entry.version(), tombstone == null ? 0L : tombstone);
    }

    public boolean applyFile(String path, long version, byte[] content) throws IOException {
        final var lock = ManifestLocks.write(manifest, projectName, waitMillis);
        try {
            if (versionOf(path) >= version) {
                return true;
            }
            if (blocksTheWayTo(path, false)) {
                return false;
            }
            tree.ensureFolders();
            final var tmpName = "r" + version + "-" + TEMP_NAMES.incrementAndGet() + ".part";
            final var tmp = tree.tmpFolder().resolve(tmpName);
            ProjectFileWrites.writeTemp(tmp, content);
            try {
                ManifestLog.commit(tree, manifest,
                        List.of(ManifestLog.pending(path, version, content.length, tmpName)));
            } catch (IOException | RuntimeException e) {
                Files.deleteIfExists(tmp);
                throw e;
            }
            manifest.put(path, FileEntry.file(version, content.length));
            applied(() -> FileLocks.moveIntoPlace(tmp, tree.resolve(path)));
            return true;
        } finally {
            lock.unlock();
        }
    }

    public boolean applyAppend(String path, long version, long baseVersion, long baseSize, byte[] delta)
            throws IOException {
        final var lock = ManifestLocks.write(manifest, projectName, waitMillis);
        try {
            if (versionOf(path) >= version) {
                return true;
            }
            final var existing = manifest.get(path);
            if (existing == null || existing.isDirectory() || existing.version() != baseVersion
                    || existing.size() != baseSize) {
                return false;
            }
            final var target = tree.resolve(path);
            final var newSize = baseSize + delta.length;
            try (var channel = FileChannel.open(target, StandardOpenOption.WRITE)) {
                try {
                    ChunkedChannelWrites.writeAt(channel, baseSize, delta, 0, delta.length);
                    SyncedWrites.content(channel, target);
                    ManifestLog.commit(tree, manifest, List.of(ManifestLog.file(path, version, newSize)));
                } catch (IOException | RuntimeException e) {
                    channel.truncate(baseSize);
                    throw e;
                }
            }
            manifest.put(path, FileEntry.file(version, newSize));
            return true;
        } finally {
            lock.unlock();
        }
    }

    public boolean applyDirectory(String path, long version) throws IOException {
        final var lock = ManifestLocks.write(manifest, projectName, waitMillis);
        try {
            if (versionOf(path) >= version) {
                return true;
            }
            final var existing = manifest.get(path);
            if (existing == null || !existing.isDirectory()) {
                if (blocksTheWayTo(path, true)) {
                    return false;
                }
                tree.ensureFolders();
                final var physical = tree.resolve(path);
                if (!Files.isDirectory(physical, LinkOption.NOFOLLOW_LINKS) && !OwnerOnly.createDirectory(physical)) {
                    throw new IOException("Could not create the folder " + physical);
                }
            }
            ManifestLog.commit(tree, manifest, List.of(ManifestLog.directory(path, version)));
            manifest.put(path, FileEntry.directory(version));
            return true;
        } finally {
            lock.unlock();
        }
    }

    public boolean applyTombstone(String path, long version) throws IOException {
        final var lock = ManifestLocks.write(manifest, projectName, waitMillis);
        try {
            if (versionOf(path) >= version) {
                return true;
            }
            final var existing = manifest.get(path);
            if (existing != null && existing.isDirectory() && manifest.hasChildren(ProjectFilePath.ofStored(path))) {
                return false;
            }
            tree.ensureFolders();
            ManifestLog.commit(tree, manifest, List.of(ManifestLog.tombstone(path, version)));
            manifest.bury(path, version);
            if (existing != null) {
                final var physical = tree.resolve(path);
                applied(() -> {
                    if (Files.exists(physical, LinkOption.NOFOLLOW_LINKS)
                            && !FileTreeDeleter.delete(physical.toFile())) {
                        throw new IOException("Could not delete " + physical);
                    }
                });
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    public boolean applyMove(String from, String to, long version) throws IOException {
        final var lock = ManifestLocks.write(manifest, projectName, waitMillis);
        try {
            final var source = manifest.get(from);
            final var target = ProjectFilePath.ofStored(to);
            if (versionOf(to) >= version) {
                return true;
            }
            if (source == null || source.version() >= version || manifest.get(to) != null || missesAParent(target)) {
                return false;
            }
            final var fromPath = ProjectFilePath.ofStored(from);
            final var moved = new ArrayList<String>();
            moved.add(from);
            moved.addAll(manifest.subtree(fromPath).keySet());
            final var records = new ArrayList<String>(2 * moved.size() + 1);
            final var placed = new TreeMap<String, FileEntry>();
            for (final var oldPath : moved) {
                final var entry = manifest.get(oldPath);
                final var newPath = ProjectFilePath.ofStored(oldPath).rebased(fromPath, target).toString();
                placed.put(newPath,
                        entry.isDirectory() ? FileEntry.directory(version) : FileEntry.file(version, entry.size()));
            }
            for (final var oldPath : moved.reversed()) {
                records.add(ManifestLog.tombstone(oldPath, version));
            }
            records.add(ManifestLog.move(from, to));
            placed.forEach((newPath, entry) -> records.add(ManifestLog.entry(newPath, entry)));
            ManifestLog.commit(tree, manifest, records);
            moved.reversed().forEach(oldPath -> manifest.bury(oldPath, version));
            placed.forEach(manifest::put);
            applied(() -> FileLocks.moveIntoPlace(tree.resolve(from), tree.resolve(to)));
            return true;
        } finally {
            lock.unlock();
        }
    }

    private boolean missesAParent(ProjectFilePath path) {
        for (final var ancestor : path.ancestors()) {
            final var entry = manifest.get(ancestor);
            if (entry == null || !entry.isDirectory()) {
                return true;
            }
        }
        return false;
    }

    private boolean blocksTheWayTo(String path, boolean directory) throws IOException {
        final var parsed = ProjectFilePath.ofStored(path);
        if (missesAParent(parsed)) {
            return true;
        }
        final var existing = manifest.get(path);
        if (existing == null) {
            final var sibling = manifest.siblingWithFoldedName(parsed);
            return sibling != null && !sibling.equals(parsed.name());
        }
        if (existing.isDirectory() == directory) {
            return false;
        }
        if (existing.isDirectory() && manifest.hasChildren(parsed)) {
            return true;
        }
        final var physical = tree.resolve(path);
        if (Files.exists(physical, LinkOption.NOFOLLOW_LINKS) && !FileTreeDeleter.delete(physical.toFile())) {
            throw new IOException("Could not delete " + physical);
        }
        manifest.forget(path);
        return false;
    }

    @FunctionalInterface
    private interface PhysicalStep {
        void run() throws IOException;
    }

    private void applied(PhysicalStep step) throws IOException {
        try {
            step.run();
        } catch (IOException e) {
            store.forgetLoaded(projectName);
            throw new FileApplyPendingException(projectName, e);
        }
    }

    public List<Described> describe() throws IOException {
        final var lock = ManifestLocks.read(manifest, projectName, waitMillis);
        try {
            final var described = new ArrayList<Described>(manifest.entryCount() + manifest.tombstones().size());
            for (final var entry : manifest.entries().entrySet()) {
                final var value = entry.getValue();
                described.add(new Described(entry.getKey(), value.kind(), value.version(), value.size(), false));
            }
            for (final var tombstone : new TreeMap<>(manifest.tombstones()).entrySet()) {
                described.add(new Described(tombstone.getKey(), FileKind.FILE, tombstone.getValue(), 0L, true));
            }
            return described;
        } finally {
            lock.unlock();
        }
    }

    public static String summaryOf(List<Described> described) {
        try {
            final var digest = MessageDigest.getInstance("SHA-256");
            for (final var entry : described) {
                digest.update((entry.path() + '|' + (entry.tombstone() ? "X" : entry.kind().name()) + '|'
                        + entry.version() + '|' + entry.size() + '\n').getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
