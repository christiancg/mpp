package org.ccg.mpp.fs;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import org.ccg.mpp.data.FileEntry;
import org.ccg.mpp.data.ProjectFilePath;
import org.ccg.mpp.ex.InvalidFilePathException;
import org.ccg.mpp.log.Logger;

final class FileTreeRedo {
    private static final Logger logger = Logger.logFor(FileTreeRedo.class);
    private static final String SEPARATOR = "/";
    private final ProjectFileTree tree;
    private final FileManifest manifest;
    private final LongSupplier versions;

    FileTreeRedo(ProjectFileTree tree, FileManifest manifest, LongSupplier versions) {
        this.tree = tree;
        this.manifest = manifest;
        this.versions = versions;
    }

    void run() throws IOException {
        finishMoves();
        finishReplaces();
        removeBuried();
        recreateFolders();
        reconcileFiles();
        adoptUntracked();
        emptyTmp();
        manifest.clearPendingWork();
    }

    private void finishMoves() throws IOException {
        for (final var move : manifest.pendingMoves()) {
            final var from = tree.resolve(move.from());
            final var to = tree.resolve(move.to());
            if (manifest.get(move.from()) != null || manifest.get(move.to()) == null
                    || !Files.exists(from, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            OwnerOnly.createDirectories(tree.folderOf(to));
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
            SyncedWrites.entriesOfLanded(tree.folderOf(from), "Finishing the move of " + move.from());
            SyncedWrites.entriesOfLanded(tree.folderOf(to), "Finishing the move to " + move.to());
            logger.info("Finished moving " + move.from() + " to " + move.to() + " in " + tree.root());
        }
    }

    private void finishReplaces() throws IOException {
        for (final var pending : manifest.pendingReplaces().entrySet()) {
            final var tmp = tree.tmpFolder().resolve(pending.getValue().tmpName());
            if (!Files.exists(tmp, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            final var current = manifest.get(pending.getKey());
            if (current == null || current.isDirectory() || current.version() != pending.getValue().version()) {
                Files.delete(tmp);
                continue;
            }
            final var target = tree.resolve(pending.getKey());
            OwnerOnly.createDirectories(tree.folderOf(target));
            FileLocks.moveIntoPlace(tmp, target);
            logger.info("Finished writing " + pending.getKey() + " in " + tree.root());
        }
    }

    private void removeBuried() throws IOException {
        final var buried = new ArrayList<>(manifest.tombstones().keySet());
        buried.sort(Comparator.reverseOrder());
        for (final var path : buried) {
            if (manifest.get(path) != null) {
                continue;
            }
            final var physical = tree.resolve(path);
            if (Files.exists(physical, LinkOption.NOFOLLOW_LINKS)) {
                FileTreeDeleter.delete(physical.toFile());
                logger.info("Finished deleting " + path + " in " + tree.root());
            }
        }
    }

    private void recreateFolders() throws IOException {
        for (final var entry : manifest.entries().entrySet()) {
            if (entry.getValue().isDirectory()) {
                final var physical = tree.resolve(entry.getKey());
                if (!Files.isDirectory(physical, LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.exists(physical, LinkOption.NOFOLLOW_LINKS)) {
                        continue;
                    }
                    OwnerOnly.createDirectories(physical);
                }
            }
        }
    }

    private void reconcileFiles() throws IOException {
        for (final var entry : List.copyOf(manifest.entries().entrySet())) {
            final var path = entry.getKey();
            final var recorded = entry.getValue();
            final var physical = tree.resolve(path);
            final var isFolder = Files.isDirectory(physical, LinkOption.NOFOLLOW_LINKS);
            if (!Files.exists(physical, LinkOption.NOFOLLOW_LINKS) || isFolder != recorded.isDirectory()) {
                logger.warning("The project file " + path + " in " + tree.root() + " is no longer there as the"
                        + " manifest records it; it is forgotten, and a clustered node fetches it back from a peer");
                manifest.forget(path);
                continue;
            }
            if (!recorded.isDirectory()) {
                reconcileLength(path, recorded, physical);
            }
        }
    }

    private void reconcileLength(String path, FileEntry recorded, Path physical) throws IOException {
        final var length = Files.size(physical);
        if (length > recorded.size()) {
            try (var channel = FileChannel.open(physical, StandardOpenOption.WRITE)) {
                channel.truncate(recorded.size());
                SyncedWrites.content(channel, physical);
            }
            logger.info("Cut " + path + " in " + tree.root() + " back to its committed " + recorded.size()
                    + " bytes; an append beyond them never committed");
        } else if (length < recorded.size()) {
            logger.warning("The project file " + path + " in " + tree.root() + " holds " + length + " bytes but its"
                    + " manifest records " + recorded.size() + "; its current content is adopted as a new version");
            manifest.put(path, FileEntry.file(versions.getAsLong(), length));
        }
    }

    private void adoptUntracked() throws IOException {
        if (!Files.isDirectory(tree.root(), LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        final List<Path> found;
        try (var walk = Files.walk(tree.root())) {
            found = walk.sorted().toList();
        }
        final var leftOut = new ArrayList<Path>();
        for (final var physical : found) {
            if (physical.equals(tree.root()) || leftOut.stream().anyMatch(physical::startsWith)) {
                continue;
            }
            final var attributes = Files.readAttributes(physical, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!adopt(physical, attributes) && attributes.isDirectory()) {
                leftOut.add(physical);
            }
        }
    }

    private boolean adopt(Path physical, BasicFileAttributes attributes) {
        final var relative = tree.root().relativize(physical).toString()
                .replace(physical.getFileSystem().getSeparator(), SEPARATOR);
        final ProjectFilePath path;
        try {
            path = ProjectFilePath.parse(relative);
        } catch (InvalidFilePathException e) {
            logger.warning("Left " + physical + " out of the project's files: " + e.getMessage());
            return false;
        }
        if (attributes.isSymbolicLink() || attributes.isOther()) {
            logger.warning("Left " + physical + " out of the project's files: it is not a plain file or folder");
            return false;
        }
        final var stored = path.toString();
        if (manifest.get(stored) != null) {
            return true;
        }
        final var sibling = manifest.siblingWithFoldedName(path);
        if (sibling != null && !sibling.equals(path.name())) {
            logger.warning("Left " + physical + " out of the project's files: '" + sibling
                    + "' already exists in its folder under the same name in another letter case");
            return false;
        }
        final var adopted = attributes.isDirectory()
                ? FileEntry.directory(versions.getAsLong())
                : FileEntry.file(versions.getAsLong(), attributes.size());
        manifest.put(stored, adopted);
        logger.info("Adopted " + physical + ", which the file manifest did not record");
        return true;
    }

    private void emptyTmp() throws IOException {
        if (!Files.isDirectory(tree.tmpFolder())) {
            return;
        }
        try (var leftovers = Files.list(tree.tmpFolder())) {
            for (final var leftover : leftovers.toList()) {
                Files.deleteIfExists(leftover);
            }
        }
    }

    static List<String> compacted(FileManifest manifest, boolean keepTombstones) {
        final var records = new ArrayList<String>(manifest.entryCount() + 1);
        records.add(ManifestLog.incarnation(manifest.incarnation()));
        for (final var entry : manifest.entries().entrySet()) {
            records.add(ManifestLog.entry(entry.getKey(), entry.getValue()));
        }
        if (keepTombstones) {
            for (final Map.Entry<String, Long> tombstone : manifest.tombstones().entrySet()) {
                records.add(ManifestLog.tombstone(tombstone.getKey(), tombstone.getValue()));
            }
        }
        return records;
    }
}
