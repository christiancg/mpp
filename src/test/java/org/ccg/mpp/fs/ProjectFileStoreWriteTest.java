package org.ccg.mpp.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.ccg.mpp.config.ConfigKey;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.data.FileEntry;
import org.ccg.mpp.data.FileKind;
import org.ccg.mpp.data.FileWriteMode;
import org.ccg.mpp.data.ProjectFilePath;
import org.ccg.mpp.ex.ProjectFileException;
import org.ccg.mpp.ex.ProjectFileException.Reason;
import org.ccg.mpp.test.ProjectFilesFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ProjectFileStoreWriteTest {
    private static final String PROJECT = "storeproj";
    private static final ProjectFileWrites.WriteSpec REPLACE = new ProjectFileWrites.WriteSpec(FileWriteMode.REPLACE,
            null, true);
    private static final ProjectFileWrites.WriteSpec APPEND = new ProjectFileWrites.WriteSpec(FileWriteMode.APPEND,
            null, true);
    private static final ProjectFileWrites.WriteSpec CREATE = new ProjectFileWrites.WriteSpec(FileWriteMode.CREATE,
            null, true);
    private final Configuration configuration = Configuration.getInstance();
    private ProjectFileStore store;

    @BeforeEach
    void start() throws Exception {
        store = ProjectFilesFixture.start();
    }

    @AfterEach
    void stop() {
        ProjectFilesFixture.stop();
    }

    private static ProjectFilePath path(String raw) throws Exception {
        return ProjectFilePath.parse(raw);
    }

    private List<FileChange> write(String raw, String text, ProjectFileWrites.WriteSpec spec) throws Exception {
        return store.writes(PROJECT).write(path(raw), text.getBytes(StandardCharsets.UTF_8), spec,
                ProjectFilesFixture.nextVersion());
    }

    private String read(String raw) throws Exception {
        return new String(store.read(PROJECT, path(raw), 0, Integer.MAX_VALUE).bytes(), StandardCharsets.UTF_8);
    }

    private Reason refusal(ThrowingCall call) {
        return assertThrows(ProjectFileException.class, call::run).reason();
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }

    @Test
    public void test_replace_creates_parents_and_reads_back() throws Exception {
        final var changes = write("/public/css/site.css", "body{}", REPLACE);
        assertEquals(List.of(FileChange.Type.DIRECTORY, FileChange.Type.DIRECTORY, FileChange.Type.FILE),
                changes.stream().map(FileChange::type).toList());
        assertEquals("body{}", read("/public/css/site.css"));
        assertEquals(FileKind.DIRECTORY, store.stat(PROJECT, path("/public")).kind());
        assertEquals(new ProjectFileStore.Totals(3, 6), store.totals(PROJECT));
        assertTrue(Files.isRegularFile(store.root().resolve(PROJECT).resolve("public/css/site.css")));
        write("/public/css/site.css", "a", REPLACE);
        assertEquals("a", read("/public/css/site.css"));
        assertEquals(1, store.totals(PROJECT).bytes());
    }

    @Test
    public void test_create_refuses_an_existing_file_and_if_version_guards_a_write() throws Exception {
        final var version = write("/a.txt", "1", CREATE).getLast().version();
        assertEquals(Reason.EXISTS, refusal(() -> write("/a.txt", "2", CREATE)));
        final var stale = new ProjectFileWrites.WriteSpec(FileWriteMode.REPLACE, version - 1, true);
        assertEquals(Reason.VERSION_CONFLICT, refusal(() -> write("/a.txt", "2", stale)));
        final var mustBeAbsent = new ProjectFileWrites.WriteSpec(FileWriteMode.REPLACE, 0L, true);
        assertEquals(Reason.VERSION_CONFLICT, refusal(() -> write("/a.txt", "2", mustBeAbsent)));
        write("/a.txt", "3", new ProjectFileWrites.WriteSpec(FileWriteMode.REPLACE, version, true));
        assertEquals("3", read("/a.txt"));
        write("/b.txt", "new", mustBeAbsent);
        assertEquals("new", read("/b.txt"));
    }

    @Test
    public void test_append_extends_and_creates() throws Exception {
        write("/log.txt", "one", APPEND);
        final var changes = write("/log.txt", "-two", APPEND);
        assertEquals(FileChange.Type.APPEND, changes.getLast().type());
        assertEquals(3, changes.getLast().baseSize());
        assertEquals("one-two", read("/log.txt"));
        assertEquals(7, store.stat(PROJECT, path("/log.txt")).size());
    }

    @Test
    public void test_kinds_parents_and_letter_case_are_enforced() throws Exception {
        write("/dir/file", "x", REPLACE);
        assertEquals(Reason.KIND_MISMATCH, refusal(() -> write("/dir", "x", REPLACE)));
        assertEquals(Reason.KIND_MISMATCH, refusal(() -> write("/dir/file/child", "x", REPLACE)));
        assertEquals(Reason.KIND_MISMATCH, refusal(() -> write("/", "x", REPLACE)));
        assertEquals(Reason.NOT_FOUND, refusal(
                () -> write("/missing/x", "x", new ProjectFileWrites.WriteSpec(FileWriteMode.REPLACE, null, false))));
        assertEquals(Reason.CASE_COLLISION, refusal(() -> write("/DIR/other", "x", REPLACE)));
        assertEquals(Reason.CASE_COLLISION, refusal(() -> write("/dir/FILE", "x", REPLACE)));
    }

    @Test
    public void test_size_and_quotas_are_enforced() throws Exception {
        configuration.override(ConfigKey.FILES_MAX_FILE_SIZE, "4b");
        assertEquals(Reason.TOO_LARGE, refusal(() -> write("/big", "12345", REPLACE)));
        write("/small", "1234", REPLACE);
        assertEquals(Reason.TOO_LARGE, refusal(() -> write("/small", "5", APPEND)));
        configuration.override(ConfigKey.FILES_MAX_FILE_SIZE, "1Mb");
        configuration.override(ConfigKey.FILES_MAX_BYTES_PER_PROJECT, "6b");
        assertEquals(Reason.QUOTA_EXCEEDED, refusal(() -> write("/more", "123", REPLACE)));
        write("/small", "1", REPLACE);
        configuration.override(ConfigKey.FILES_MAX_BYTES_PER_PROJECT, "0");
        configuration.override(ConfigKey.FILES_MAX_ENTRIES_PER_PROJECT, "2");
        write("/second", "", REPLACE);
        assertEquals(Reason.QUOTA_EXCEEDED, refusal(() -> write("/third", "", REPLACE)));
        assertEquals(Reason.QUOTA_EXCEEDED, refusal(
                () -> store.writes(PROJECT).makeDirectory(path("/d"), true, ProjectFilesFixture.nextVersion())));
    }

    @Test
    public void test_make_directory_recursive_and_not() throws Exception {
        final var writes = store.writes(PROJECT);
        assertEquals(3, writes.makeDirectory(path("/a/b/c"), true, ProjectFilesFixture.nextVersion()).size());
        assertEquals(List.of(), writes.makeDirectory(path("/a/b"), true, ProjectFilesFixture.nextVersion()));
        assertEquals(List.of(), writes.makeDirectory(path("/"), true, ProjectFilesFixture.nextVersion()));
        assertEquals(Reason.EXISTS, refusal(() -> writes.makeDirectory(path("/a"), false, 1L)));
        assertEquals(Reason.NOT_FOUND, refusal(() -> writes.makeDirectory(path("/x/y"), false, 1L)));
        assertEquals(Reason.CASE_COLLISION, refusal(() -> writes.makeDirectory(path("/A"), true, 1L)));
        write("/file", "x", REPLACE);
        assertEquals(Reason.EXISTS, refusal(() -> writes.makeDirectory(path("/file"), true, 1L)));
        assertTrue(Files.isDirectory(store.root().resolve(PROJECT).resolve("a/b/c")));
    }

    @Test
    public void test_delete_files_folders_and_the_root() throws Exception {
        final var writes = store.writes(PROJECT);
        write("/d/one", "1", REPLACE);
        write("/d/two", "2", REPLACE);
        write("/top", "t", REPLACE);
        assertEquals(List.of(), writes.delete(path("/nothing"), false, ProjectFilesFixture.nextVersion()));
        assertEquals(Reason.NOT_EMPTY, refusal(() -> writes.delete(path("/d"), false, 1L)));
        assertEquals(1, writes.delete(path("/d/one"), false, ProjectFilesFixture.nextVersion()).size());
        final var removed = writes.delete(path("/d"), true, ProjectFilesFixture.nextVersion());
        assertEquals(List.of("/d/two", "/d"), removed.stream().map(FileChange::path).toList());
        assertFalse(Files.exists(store.root().resolve(PROJECT).resolve("d")));
        assertEquals(Reason.NOT_EMPTY, refusal(() -> writes.delete(path("/"), false, 1L)));
        assertEquals(1, writes.delete(path("/"), true, ProjectFilesFixture.nextVersion()).size());
        assertEquals(0, store.totals(PROJECT).entries());
        assertTrue(Files.isDirectory(store.root().resolve(PROJECT)));
        assertEquals(List.of(), writes.delete(path("/"), true, ProjectFilesFixture.nextVersion()));
    }

    @Test
    public void test_move_files_and_folders() throws Exception {
        final var writes = store.writes(PROJECT);
        write("/src/a.txt", "a", REPLACE);
        write("/src/sub/b.txt", "b", REPLACE);
        final var moved = writes.move(path("/src"), path("/dst/inner"), false, true, ProjectFilesFixture.nextVersion());
        assertEquals(FileChange.Type.MOVE, moved.getLast().type());
        assertEquals(4, moved.getLast().size());
        assertNull(store.stat(PROJECT, path("/src")));
        assertEquals("b", read("/dst/inner/sub/b.txt"));
        assertEquals(Reason.INVALID_MOVE,
                refusal(() -> writes.move(path("/dst"), path("/dst/inner/x"), false, true, 1L)));
        assertEquals(Reason.INVALID_MOVE, refusal(() -> writes.move(path("/"), path("/x"), false, true, 1L)));
        assertEquals(Reason.NOT_FOUND, refusal(() -> writes.move(path("/nope"), path("/x"), false, true, 1L)));
        assertEquals(List.of(), writes.move(path("/dst"), path("/dst"), false, true, 1L));
        write("/other.txt", "o", REPLACE);
        assertEquals(Reason.EXISTS,
                refusal(() -> writes.move(path("/other.txt"), path("/dst/inner/a.txt"), false, true, 1L)));
        assertEquals(Reason.KIND_MISMATCH,
                refusal(() -> writes.move(path("/other.txt"), path("/dst/inner/sub"), true, true, 1L)));
        assertEquals(Reason.NOT_EMPTY, refusal(() -> writes.move(path("/dst/inner"), path("/dst"), true, true, 1L)));
        writes.move(path("/other.txt"), path("/dst/inner/a.txt"), true, true, ProjectFilesFixture.nextVersion());
        assertEquals("o", read("/dst/inner/a.txt"));
        writes.move(path("/dst/inner/a.txt"), path("/dst/inner/A.txt"), false, true, ProjectFilesFixture.nextVersion());
        assertEquals(FileEntry.file(store.stat(PROJECT, path("/dst/inner/A.txt")).version(), 1),
                store.stat(PROJECT, path("/dst/inner/A.txt")));
        assertNull(store.stat(PROJECT, path("/dst/inner/a.txt")));
    }

    @Test
    public void test_list_pages_in_path_order() throws Exception {
        for (final var name : List.of("c", "a", "b")) {
            write("/f/" + name, name, REPLACE);
        }
        write("/f/sub/deep", "d", REPLACE);
        final var first = store.list(PROJECT, path("/f"), false, null, 2);
        assertEquals(List.of("/f/a", "/f/b"), first.entries().stream().map(FileListing.Listed::path).toList());
        assertEquals("/f/b", first.next());
        final var second = store.list(PROJECT, path("/f"), false, first.next(), 2);
        assertEquals(List.of("/f/c", "/f/sub"), second.entries().stream().map(FileListing.Listed::path).toList());
        assertEquals(6, store.list(PROJECT, ProjectFilePath.root(), true, null, 100).entries().size());
        assertEquals(Reason.KIND_MISMATCH, refusal(() -> store.list(PROJECT, path("/f/a"), false, null, 10)));
        assertEquals(Reason.NOT_FOUND, refusal(() -> store.list(PROJECT, path("/none"), false, null, 10)));
    }

    @Test
    public void test_reads_window_and_never_pass_the_end() throws Exception {
        write("/w", "0123456789", REPLACE);
        final var window = store.read(PROJECT, path("/w"), 3, 4);
        assertArrayEquals("3456".getBytes(StandardCharsets.UTF_8), window.bytes());
        assertFalse(window.reachesEnd());
        assertTrue(store.read(PROJECT, path("/w"), 8, 4).reachesEnd());
        assertEquals(0, store.read(PROJECT, path("/w"), 50, 4).bytes().length);
        assertEquals(Reason.NOT_FOUND, refusal(() -> store.read(PROJECT, path("/none"), 0, 1)));
        write("/d/x", "x", REPLACE);
        assertEquals(Reason.KIND_MISMATCH, refusal(() -> store.read(PROJECT, path("/d"), 0, 1)));
        assertEquals(Reason.KIND_MISMATCH, refusal(() -> store.read(PROJECT, ProjectFilePath.root(), 0, 1)));
    }

    @Test
    public void test_an_untouched_project_holds_nothing_and_creates_nothing() throws Exception {
        assertFalse(store.holdsFiles("emptyproj"));
        assertEquals(new ProjectFileStore.Totals(0, 0), store.totals("emptyproj"));
        assertNull(store.stat("emptyproj", path("/x")));
        assertEquals(FileKind.DIRECTORY, store.stat("emptyproj", ProjectFilePath.root()).kind());
        assertFalse(Files.exists(store.root().resolve("emptyproj")));
    }
}
