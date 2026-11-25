package org.ccg.mpp.fs;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;
import org.ccg.mpp.config.ConfigKey;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.FileEntry;
import org.ccg.mpp.data.ProjectFilePath;
import org.ccg.mpp.ex.ProjectFileException;
import org.ccg.mpp.ex.ProjectFileException.Reason;
import org.ccg.mpp.log.Logger;

public class ProjectFileStore {
    private static final Logger logger = Logger.logFor(ProjectFileStore.class);
    private final Configuration configuration = Configuration.getInstance();
    private final Map<String, FileManifest> manifests = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> loadLocks = new ConcurrentHashMap<>();
    private volatile Path filesRoot;
    private volatile LongSupplier versions = System::currentTimeMillis;
    private volatile LongConsumer versionObserver = _ -> {
    };
    private volatile ToLongFunction<String> incarnations = _ -> 0L;

    public record Totals(long entries, long bytes) {
    }

    public void useClock(LongSupplier nextVersion, LongConsumer observer) {
        this.versions = nextVersion;
        this.versionObserver = observer;
    }

    public void useIncarnations(ToLongFunction<String> projectIncarnations) {
        this.incarnations = projectIncarnations;
    }

    public void useConfiguredRoot() {
        filesRoot = Path.of(configuration.stringValue(ConfigKey.FILES_PATH)).toAbsolutePath().normalize();
        manifests.clear();
    }

    public void createRoot() throws IOException {
        useConfiguredRoot();
        OwnerOnly.createDirectories(metaFolder());
    }

    public Path root() {
        return filesRoot;
    }

    public Path metaFolder() {
        return filesRoot.resolve(Globals.FILES_META_FOLDER);
    }

    public FormatStampFile formatStamp() {
        return new FormatStampFile(() -> metaFolder().resolve(Globals.FORMAT_STAMP_FILE), () -> false);
    }

    public FileEntry stat(String projectName, ProjectFilePath path) throws IOException {
        if (path.isRoot()) {
            return FileEntry.directory(0L);
        }
        final var manifest = manifest(projectName, ManifestLocks.UNBOUNDED);
        final var lock = ManifestLocks.read(manifest, projectName, ManifestLocks.UNBOUNDED);
        try {
            return manifest.get(path);
        } finally {
            lock.unlock();
        }
    }

    public FileListing list(String projectName, ProjectFilePath folder, boolean recursive, String after, int limit)
            throws IOException, ProjectFileException {
        final var manifest = manifest(projectName, ManifestLocks.UNBOUNDED);
        final var lock = ManifestLocks.read(manifest, projectName, ManifestLocks.UNBOUNDED);
        try {
            final var listed = folder.isRoot() ? FileEntry.directory(0L) : manifest.get(folder);
            if (listed == null) {
                throw new ProjectFileException(Reason.NOT_FOUND, "No folder at " + folder);
            }
            if (!listed.isDirectory()) {
                throw new ProjectFileException(Reason.KIND_MISMATCH, folder + " is a file, not a folder");
            }
            var subtree = manifest.subtree(folder);
            if (after != null && !after.isEmpty()) {
                subtree = subtree.tailMap(after, false);
            }
            final var depth = folder.segments().size() + 1;
            final var entries = new ArrayList<FileListing.Listed>(Math.min(limit, subtree.size()));
            String next = null;
            for (final var entry : subtree.entrySet()) {
                if (!recursive && ProjectFilePath.ofStored(entry.getKey()).segments().size() != depth) {
                    continue;
                }
                if (entries.size() == limit) {
                    next = entries.getLast().path();
                    break;
                }
                entries.add(new FileListing.Listed(entry.getKey(), entry.getValue()));
            }
            return new FileListing(entries, next);
        } finally {
            lock.unlock();
        }
    }

    public FileSlice read(String projectName, ProjectFilePath path, long offset, int length)
            throws IOException, ProjectFileException {
        return read(projectName, path, offset, length, ManifestLocks.UNBOUNDED);
    }

    public FileSlice read(String projectName, ProjectFilePath path, long offset, int length, long waitMillis)
            throws IOException, ProjectFileException {
        final var manifest = manifest(projectName, waitMillis);
        final var lock = ManifestLocks.read(manifest, projectName, waitMillis);
        try {
            final var entry = requireFile(manifest, path);
            if (offset >= entry.size()) {
                return new FileSlice(entry, offset, new byte[0]);
            }
            final var count = (int) Math.min(length, entry.size() - offset);
            final var bytes = new byte[count];
            final var buffer = ByteBuffer.wrap(bytes);
            try (var channel = FileChannel.open(tree(projectName).resolve(path), StandardOpenOption.READ)) {
                var position = offset;
                while (buffer.hasRemaining()) {
                    final var read = channel.read(buffer, position);
                    if (read < 0) {
                        throw new IOException(
                                path + " in project '" + projectName + "' is shorter than its" + " manifest records");
                    }
                    position += read;
                }
            }
            return new FileSlice(entry, offset, bytes);
        } finally {
            lock.unlock();
        }
    }

    static FileEntry requireFile(FileManifest manifest, ProjectFilePath path) throws ProjectFileException {
        final var entry = path.isRoot() ? FileEntry.directory(0L) : manifest.get(path);
        if (entry == null) {
            throw new ProjectFileException(Reason.NOT_FOUND, "No file at " + path);
        }
        if (entry.isDirectory()) {
            throw new ProjectFileException(Reason.KIND_MISMATCH, path + " is a folder, not a file");
        }
        return entry;
    }

    public Totals totals(String projectName) throws IOException {
        if (!holdsFiles(projectName)) {
            return new Totals(0L, 0L);
        }
        final var manifest = manifest(projectName, ManifestLocks.UNBOUNDED);
        final var lock = ManifestLocks.read(manifest, projectName, ManifestLocks.UNBOUNDED);
        try {
            return new Totals(manifest.entryCount(), manifest.bytes());
        } finally {
            lock.unlock();
        }
    }

    public boolean holdsFiles(String projectName) {
        return manifests.containsKey(projectName) || Files.exists(tree(projectName).manifest())
                || Files.isDirectory(tree(projectName).root());
    }

    public ProjectFileWrites writes(String projectName) throws IOException {
        return new ProjectFileWrites(this, projectName, manifest(projectName, ManifestLocks.UNBOUNDED),
                tree(projectName));
    }

    public ProjectFileReplica replica(String projectName) throws IOException {
        return replica(projectName, ManifestLocks.UNBOUNDED);
    }

    public ProjectFileReplica replica(String projectName, long waitMillis) throws IOException {
        return new ProjectFileReplica(this, projectName, manifest(projectName, waitMillis), tree(projectName),
                waitMillis);
    }

    public long incarnation(String projectName) throws IOException {
        return incarnation(projectName, ManifestLocks.UNBOUNDED);
    }

    public long incarnation(String projectName, long waitMillis) throws IOException {
        return manifest(projectName, waitMillis).incarnation();
    }

    public void dropProject(String projectName) throws IOException {
        final var lock = loadLock(projectName);
        lock.lock();
        try {
            retireLoaded(projectName, ManifestLocks.UNBOUNDED);
            final var tree = tree(projectName);
            deleteIfPresent(tree.root());
            deleteIfPresent(tree.metaFolder());
        } finally {
            lock.unlock();
        }
    }

    public void quarantine(String projectName, String reason) throws IOException {
        final var lock = loadLock(projectName);
        lock.lock();
        try {
            quarantineLoaded(projectName, reason, ManifestLocks.UNBOUNDED);
        } finally {
            lock.unlock();
        }
    }

    private void quarantineLoaded(String projectName, String reason, long waitMillis) throws IOException {
        retireLoaded(projectName, waitMillis);
        final var tree = tree(projectName);
        final var suffix = Globals.QUARANTINE_INFIX + System.currentTimeMillis();
        moveAside(tree.root(), suffix);
        moveAside(tree.metaFolder(), suffix);
        logger.warning("Moved the files of project '" + projectName + "' aside to " + tree.root() + suffix + ": "
                + reason + ". Check them and delete them yourself");
    }

    private void retireLoaded(String projectName, long waitMillis) throws IOException {
        final var loaded = manifests.get(projectName);
        if (loaded == null) {
            return;
        }
        final var lock = ManifestLocks.take(loaded.lock().writeLock(), projectName, waitMillis);
        try {
            loaded.retire();
            manifests.remove(projectName, loaded);
        } finally {
            lock.unlock();
        }
    }

    private static void moveAside(Path folder, String suffix) throws IOException {
        if (Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) {
            var aside = folder.resolveSibling(folder.getFileName() + suffix);
            for (var attempt = 2; Files.exists(aside, LinkOption.NOFOLLOW_LINKS); attempt++) {
                aside = folder.resolveSibling(folder.getFileName() + suffix + "-" + attempt);
            }
            Files.move(folder, aside, StandardCopyOption.ATOMIC_MOVE);
            SyncedWrites.entriesOfLanded(folder.getParent(), "Moving " + folder + " aside");
        }
    }

    private static void deleteIfPresent(Path folder) throws IOException {
        if (Files.exists(folder, LinkOption.NOFOLLOW_LINKS) && !FileTreeDeleter.delete(folder.toFile())) {
            throw new IOException("Could not delete " + folder);
        }
    }

    void forgetLoaded(String projectName) {
        final var loaded = manifests.remove(projectName);
        if (loaded != null) {
            loaded.retire();
        }
    }

    ProjectFileTree tree(String projectName) {
        if (filesRoot == null) {
            throw new IllegalStateException("The file store has no root; createRoot() was not called");
        }
        return new ProjectFileTree(filesRoot, projectName);
    }

    FileManifest manifest(String projectName, long waitMillis) throws IOException {
        final var loaded = manifests.get(projectName);
        final var expected = incarnations.applyAsLong(projectName);
        if (loaded != null && sameLife(loaded.incarnation(), expected)) {
            return loaded;
        }
        final var lock = ManifestLocks.take(loadLock(projectName), projectName, waitMillis);
        try {
            final var current = manifests.get(projectName);
            if (current != null && sameLife(current.incarnation(), expected)) {
                return current;
            }
            if (current != null) {
                quarantineLoaded(projectName,
                        "they belong to an earlier life of the project, which was dropped and created again",
                        waitMillis);
            }
            final var manifest = load(projectName, expected);
            manifests.put(projectName, manifest);
            return manifest;
        } finally {
            lock.unlock();
        }
    }

    private FileManifest load(String projectName, long expected) throws IOException {
        var tree = tree(projectName);
        var manifest = new FileManifest();
        ManifestLog.load(tree.manifest(), manifest);
        if (!sameLife(manifest.incarnation(), expected)) {
            quarantineLoaded(projectName,
                    "they belong to incarnation " + manifest.incarnation()
                            + " of the project, which was dropped; the project is now incarnation " + expected,
                    ManifestLocks.UNBOUNDED);
            tree = tree(projectName);
            manifest = new FileManifest();
        }
        if (manifest.incarnation() == 0L) {
            manifest.setIncarnation(expected);
        }
        if (Files.exists(tree.manifest()) || Files.isDirectory(tree.root())) {
            tree.ensureFolders();
            new FileTreeRedo(tree, manifest, versions).run();
            final var records = FileTreeRedo.compacted(manifest, keepsTombstones());
            ManifestLog.rewrite(tree.manifest(), records);
            manifest.resetLogLines(records.size());
            if (!keepsTombstones()) {
                manifest.tombstones().clear();
            }
        }
        versionObserver.accept(manifest.highestVersion());
        return manifest;
    }

    void compactIfDue(FileManifest manifest, ProjectFileTree tree) throws IOException {
        final var live = manifest.entryCount() + manifest.tombstones().size() + 1;
        if (manifest.logLines() <= 2 * live + Globals.FILE_MANIFEST_COMPACT_FLOOR) {
            return;
        }
        final var records = FileTreeRedo.compacted(manifest, keepsTombstones());
        ManifestLog.rewrite(tree.manifest(), records);
        manifest.resetLogLines(records.size());
        if (!keepsTombstones()) {
            manifest.tombstones().clear();
        }
    }

    public void collectTombstones(String projectName, long olderThanVersion, long waitMillis) throws IOException {
        final var manifest = manifest(projectName, waitMillis);
        final var lock = ManifestLocks.write(manifest, projectName, waitMillis);
        try {
            final List<String> expired = manifest.tombstones().entrySet().stream()
                    .filter(tombstone -> tombstone.getValue() < olderThanVersion).map(Map.Entry::getKey).toList();
            if (expired.isEmpty()) {
                return;
            }
            expired.forEach(manifest::forgetTombstone);
            final var tree = tree(projectName);
            final var records = FileTreeRedo.compacted(manifest, true);
            ManifestLog.rewrite(tree.manifest(), records);
            manifest.resetLogLines(records.size());
        } finally {
            lock.unlock();
        }
    }

    boolean keepsTombstones() {
        return configuration.booleanValue(ConfigKey.CLUSTER_ENABLED);
    }

    private ReentrantLock loadLock(String projectName) {
        return loadLocks.computeIfAbsent(projectName, _ -> new ReentrantLock());
    }

    private static boolean sameLife(long recorded, long expected) {
        return recorded == 0L || expected == 0L || recorded == expected;
    }

    long maxFileSize() {
        return configuration.sizeValue(ConfigKey.FILES_MAX_FILE_SIZE);
    }

    long maxBytesPerProject() {
        return configuration.sizeValue(ConfigKey.FILES_MAX_BYTES_PER_PROJECT);
    }

    long maxEntriesPerProject() {
        return configuration.intValue(ConfigKey.FILES_MAX_ENTRIES_PER_PROJECT);
    }
}
