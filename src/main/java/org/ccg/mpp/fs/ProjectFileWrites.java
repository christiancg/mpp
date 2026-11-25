package org.ccg.mpp.fs;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.ccg.mpp.data.FileEntry;
import org.ccg.mpp.data.FileWriteMode;
import org.ccg.mpp.data.ProjectFilePath;
import org.ccg.mpp.ex.FileApplyPendingException;
import org.ccg.mpp.ex.ProjectFileException;
import org.ccg.mpp.ex.ProjectFileException.Reason;
import org.ccg.mpp.fs.io.ChunkedChannelWrites;

public final class ProjectFileWrites {
    private static final AtomicLong TEMP_NAMES = new AtomicLong();
    private static final String TEMP_SUFFIX = ".part";
    private final ProjectFileStore store;
    private final String projectName;
    private final FileManifest manifest;
    private final ProjectFileTree tree;
    private final FileWriteChecks checks;

    public record WriteSpec(FileWriteMode mode, Long ifVersion, boolean createParents) {
    }

    ProjectFileWrites(ProjectFileStore store, String projectName, FileManifest manifest, ProjectFileTree tree) {
        this.store = store;
        this.projectName = projectName;
        this.manifest = manifest;
        this.tree = tree;
        this.checks = new FileWriteChecks(store, manifest);
    }

    public List<FileChange> write(ProjectFilePath path, byte[] content, WriteSpec spec, long version)
            throws IOException, ProjectFileException {
        final var lock = ManifestLocks.write(manifest, projectName, ManifestLocks.UNBOUNDED);
        try {
            final var existing = checks.writable(path, spec);
            final var parents = checks.missingParents(path, spec.createParents());
            final var appending = spec.mode() == FileWriteMode.APPEND && existing != null;
            final var newSize = appending ? existing.size() + content.length : content.length;
            checks.withinLimits(newSize, existing, parents.size() + (existing == null ? 1 : 0));
            tree.ensureFolders();
            createFolders(parents);
            final var changes = folderChanges(parents, version);
            if (appending) {
                append(path, content, existing, version, newSize);
                changes.add(FileChange.append(path.toString(), version, newSize, existing.version(), existing.size()));
            } else {
                replace(path, content, parents, version);
                changes.add(FileChange.file(path.toString(), version, newSize));
            }
            store.compactIfDue(manifest, tree);
            return changes;
        } finally {
            lock.unlock();
        }
    }

    private void replace(ProjectFilePath path, byte[] content, List<ProjectFilePath> parents, long version)
            throws IOException {
        final var tmpName = version + "-" + TEMP_NAMES.incrementAndGet() + TEMP_SUFFIX;
        final var tmp = tree.tmpFolder().resolve(tmpName);
        writeTemp(tmp, content);
        final var records = folderRecords(parents, version);
        records.add(ManifestLog.pending(path.toString(), version, content.length, tmpName));
        try {
            commit(records);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        putFolders(parents, version);
        manifest.put(path.toString(), FileEntry.file(version, content.length));
        try {
            FileLocks.moveIntoPlace(tmp, tree.resolve(path));
        } catch (IOException e) {
            throw pending(e);
        }
    }

    static void writeTemp(Path tmp, byte[] content) throws IOException {
        OwnerOnly.ensureOwnerOnlyFileMovedLater(tmp);
        try (var channel = FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ChunkedChannelWrites.writeAt(channel, 0L, content, 0, content.length);
            SyncedWrites.content(channel, tmp);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    private void append(ProjectFilePath path, byte[] content, FileEntry existing, long version, long newSize)
            throws IOException {
        final var target = tree.resolve(path);
        try (var channel = FileChannel.open(target, StandardOpenOption.WRITE)) {
            try {
                ChunkedChannelWrites.writeAt(channel, existing.size(), content, 0, content.length);
                SyncedWrites.content(channel, target);
                commit(List.of(ManifestLog.file(path.toString(), version, newSize)));
            } catch (IOException | RuntimeException e) {
                cutBack(channel, existing.size(), e);
                throw e;
            }
        }
        manifest.put(path.toString(), FileEntry.file(version, newSize));
    }

    private static void cutBack(FileChannel channel, long size, Exception cause) {
        try {
            channel.truncate(size);
        } catch (IOException e) {
            cause.addSuppressed(e);
        }
    }

    public List<FileChange> makeDirectory(ProjectFilePath path, boolean recursive, long version)
            throws IOException, ProjectFileException {
        final var lock = ManifestLocks.write(manifest, projectName, ManifestLocks.UNBOUNDED);
        try {
            final var existing = path.isRoot() ? FileEntry.directory(0L) : manifest.get(path);
            if (existing != null) {
                if (recursive && existing.isDirectory()) {
                    return List.of();
                }
                throw new ProjectFileException(Reason.EXISTS, path + " already exists");
            }
            checks.requireUniqueName(path);
            final var created = new ArrayList<>(checks.missingParents(path, recursive));
            created.add(path);
            checks.withinLimits(0L, null, created.size());
            tree.ensureFolders();
            createFolders(created);
            commit(folderRecords(created, version));
            putFolders(created, version);
            store.compactIfDue(manifest, tree);
            return folderChanges(created, version);
        } finally {
            lock.unlock();
        }
    }

    public List<FileChange> delete(ProjectFilePath path, boolean recursive, long version)
            throws IOException, ProjectFileException {
        final var lock = ManifestLocks.write(manifest, projectName, ManifestLocks.UNBOUNDED);
        try {
            final var existing = path.isRoot() ? FileEntry.directory(0L) : manifest.get(path);
            if (existing == null) {
                return List.of();
            }
            if (existing.isDirectory() && !recursive && manifest.hasChildren(path)) {
                throw new ProjectFileException(Reason.NOT_EMPTY, path + " is a folder that is not empty");
            }
            final var removed = new ArrayList<>(manifest.subtree(path).descendingKeySet());
            if (!path.isRoot()) {
                removed.add(path.toString());
            }
            if (removed.isEmpty()) {
                return List.of();
            }
            final var records = new ArrayList<String>(removed.size());
            final var changes = new ArrayList<FileChange>(removed.size());
            for (final var removedPath : removed) {
                records.add(ManifestLog.tombstone(removedPath, version));
                changes.add(FileChange.tombstone(removedPath, version));
            }
            commit(records);
            removed.forEach(removedPath -> manifest.bury(removedPath, version));
            try {
                deletePhysically(path);
            } catch (IOException e) {
                throw pending(e);
            }
            store.compactIfDue(manifest, tree);
            return changes;
        } finally {
            lock.unlock();
        }
    }

    private void deletePhysically(ProjectFilePath path) throws IOException {
        final var physical = tree.resolve(path);
        if (!path.isRoot()) {
            if (Files.exists(physical, LinkOption.NOFOLLOW_LINKS) && !FileTreeDeleter.delete(physical.toFile())) {
                throw new IOException("Could not delete " + physical);
            }
            return;
        }
        if (!Files.isDirectory(physical)) {
            return;
        }
        try (var children = Files.list(physical)) {
            for (final var child : children.toList()) {
                if (!FileTreeDeleter.delete(child.toFile())) {
                    throw new IOException("Could not delete " + child);
                }
            }
        }
    }

    public List<FileChange> move(ProjectFilePath from, ProjectFilePath to, boolean overwrite, boolean createParents,
            long version) throws IOException, ProjectFileException {
        final var lock = ManifestLocks.write(manifest, projectName, ManifestLocks.UNBOUNDED);
        try {
            final var plan = checks.movable(from, to, overwrite, createParents);
            if (plan == null) {
                return List.of();
            }
            tree.ensureFolders();
            createFolders(plan.parents());
            final var records = folderRecords(plan.parents(), version);
            final var changes = folderChanges(plan.parents(), version);
            final var moved = new ArrayList<String>();
            moved.add(from.toString());
            moved.addAll(manifest.subtree(from).keySet());
            for (final var oldPath : moved.reversed()) {
                records.add(ManifestLog.tombstone(oldPath, version));
            }
            records.add(ManifestLog.move(from.toString(), to.toString()));
            final var placed = new ArrayList<String[]>(moved.size());
            for (final var oldPath : moved) {
                final var newPath = ProjectFilePath.ofStored(oldPath).rebased(from, to).toString();
                final var entry = manifest.get(oldPath);
                records.add(entry.isDirectory()
                        ? ManifestLog.directory(newPath, version)
                        : ManifestLog.file(newPath, version, entry.size()));
                placed.add(new String[]{oldPath, newPath});
            }
            commit(records);
            final var entries = new ArrayList<FileEntry>(placed.size());
            for (final var pair : placed) {
                entries.add(manifest.get(pair[0]));
            }
            for (final var oldPath : moved.reversed()) {
                manifest.bury(oldPath, version);
            }
            putFolders(plan.parents(), version);
            for (var i = 0; i < placed.size(); i++) {
                final var entry = entries.get(i);
                manifest.put(placed.get(i)[1],
                        entry.isDirectory() ? FileEntry.directory(version) : FileEntry.file(version, entry.size()));
            }
            changes.add(FileChange.move(from.toString(), to.toString(), version, moved.size()));
            try {
                movePhysically(from, to);
            } catch (IOException e) {
                throw pending(e);
            }
            store.compactIfDue(manifest, tree);
            return changes;
        } finally {
            lock.unlock();
        }
    }

    private void movePhysically(ProjectFilePath from, ProjectFilePath to) throws IOException {
        final var source = tree.resolve(from);
        final var target = tree.resolve(to);
        FileLocks.moveIntoPlace(source, target);
        final var sourceFolder = tree.folderOf(source);
        if (!sourceFolder.equals(tree.folderOf(target))) {
            SyncedWrites.entriesOfLanded(sourceFolder, "Moving " + from + " away");
        }
    }

    private void createFolders(List<ProjectFilePath> folders) throws IOException {
        for (final var folder : folders) {
            final var physical = tree.resolve(folder);
            if (!Files.isDirectory(physical, LinkOption.NOFOLLOW_LINKS) && !OwnerOnly.createDirectory(physical)) {
                throw new IOException("Could not create the folder " + physical);
            }
        }
    }

    private static List<String> folderRecords(List<ProjectFilePath> folders, long version) {
        final var records = new ArrayList<String>(folders.size() + 1);
        for (final var folder : folders) {
            records.add(ManifestLog.directory(folder.toString(), version));
        }
        return records;
    }

    private static List<FileChange> folderChanges(List<ProjectFilePath> folders, long version) {
        final var changes = new ArrayList<FileChange>(folders.size() + 1);
        for (final var folder : folders) {
            changes.add(FileChange.directory(folder.toString(), version));
        }
        return changes;
    }

    private void putFolders(List<ProjectFilePath> folders, long version) {
        for (final var folder : folders) {
            manifest.put(folder.toString(), FileEntry.directory(version));
        }
    }

    private void commit(List<String> records) throws IOException {
        ManifestLog.commit(tree, manifest, records);
    }

    private FileApplyPendingException pending(IOException failure) {
        store.forgetLoaded(projectName);
        return new FileApplyPendingException(projectName, failure);
    }
}
