package org.ccg.mpp.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import org.ccg.mpp.config.ConfigKey;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.FileEntry;
import org.ccg.mpp.data.FileWriteMode;
import org.ccg.mpp.data.ProjectFilePath;
import org.ccg.mpp.fs.io.OwnerOnlyFiles;
import org.ccg.mpp.test.ProjectFilesFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ProjectFileStoreRedoTest {
    private static final String PROJECT = "redoproj";
    private static final ProjectFileWrites.WriteSpec REPLACE = new ProjectFileWrites.WriteSpec(FileWriteMode.REPLACE,
            null, true);
    private ProjectFileStore store;

    @BeforeEach
    void start() throws Exception {
        store = ProjectFilesFixture.start();
    }

    @AfterEach
    void stop() {
        store.useIncarnations(_ -> 0L);
        ProjectFilesFixture.stop();
    }

    private Path tree() {
        return store.root().resolve(PROJECT);
    }

    private Path meta() {
        return store.metaFolder().resolve(PROJECT);
    }

    private void restart() throws IOException {
        store.createRoot();
        store.totals(PROJECT);
    }

    private void write(String raw, String text) throws Exception {
        store.writes(PROJECT).write(ProjectFilePath.parse(raw), text.getBytes(StandardCharsets.UTF_8), REPLACE,
                ProjectFilesFixture.nextVersion());
    }

    private void appendRecords(String... records) throws IOException {
        ManifestLog.append(meta().resolve(Globals.FILES_MANIFEST_FILE), List.of(records));
    }

    private FileEntry stat(String raw) throws Exception {
        return store.stat(PROJECT, ProjectFilePath.parse(raw));
    }

    @Test
    public void test_written_files_survive_a_restart() throws Exception {
        write("/keep/a.txt", "kept");
        restart();
        assertEquals(4, stat("/keep/a.txt").size());
        assertEquals(2, store.totals(PROJECT).entries());
    }

    @Test
    public void test_a_committed_replace_whose_rename_never_ran_is_finished() throws Exception {
        write("/seed", "s");
        final var tmp = meta().resolve(Globals.FILES_TEMP_FOLDER).resolve("99-1.part");
        Files.writeString(tmp, "fresh");
        appendRecords(ManifestLog.pending("/late.txt", ProjectFilesFixture.nextVersion(), 5, "99-1.part"));
        restart();
        assertEquals(5, stat("/late.txt").size());
        assertEquals("fresh", Files.readString(tree().resolve("late.txt")));
        assertFalse(Files.exists(tmp));
    }

    @Test
    public void test_a_stale_pending_temp_file_is_discarded() throws Exception {
        write("/seed", "s");
        final var tmp = meta().resolve(Globals.FILES_TEMP_FOLDER).resolve("1-1.part");
        Files.writeString(tmp, "old");
        appendRecords(ManifestLog.pending("/p", 1, 3, "1-1.part"));
        appendRecords(ManifestLog.file("/p", ProjectFilesFixture.nextVersion(), 3));
        Files.writeString(tree().resolve("p"), "new");
        restart();
        assertEquals("new", Files.readString(tree().resolve("p")));
        assertFalse(Files.exists(tmp));
    }

    @Test
    public void test_an_uncommitted_append_is_cut_back() throws Exception {
        write("/log", "abc");
        Files.writeString(tree().resolve("log"), "abc-uncommitted");
        restart();
        assertEquals("abc", Files.readString(tree().resolve("log")));
    }

    @Test
    public void test_a_shorter_file_and_a_vanished_file_are_reconciled() throws Exception {
        write("/short", "abcdef");
        write("/gone", "x");
        write("/kind", "x");
        Files.writeString(tree().resolve("short"), "ab");
        Files.delete(tree().resolve("gone"));
        Files.delete(tree().resolve("kind"));
        Files.createDirectory(tree().resolve("kind"));
        restart();
        assertEquals(2, stat("/short").size());
        assertNull(stat("/gone"));
        assertTrue(stat("/kind").isDirectory());
    }

    @Test
    public void test_a_logged_delete_and_move_are_finished() throws Exception {
        write("/del/x", "x");
        write("/mv/y", "y");
        final var version = ProjectFilesFixture.nextVersion();
        appendRecords(ManifestLog.tombstone("/del/x", version), ManifestLog.tombstone("/del", version));
        appendRecords(ManifestLog.tombstone("/mv/y", version), ManifestLog.tombstone("/mv", version),
                ManifestLog.move("/mv", "/moved"), ManifestLog.directory("/moved", version),
                ManifestLog.file("/moved/y", version, 1));
        restart();
        assertFalse(Files.exists(tree().resolve("del")));
        assertFalse(Files.exists(tree().resolve("mv")));
        assertEquals("y", Files.readString(tree().resolve("moved/y")));
        assertNull(stat("/del"));
        assertNotNull(stat("/moved/y"));
    }

    @Test
    public void test_a_logged_folder_missing_on_disk_is_recreated() throws Exception {
        write("/seed", "s");
        appendRecords(ManifestLog.directory("/made", ProjectFilesFixture.nextVersion()));
        restart();
        assertTrue(Files.isDirectory(tree().resolve("made")));
    }

    @Test
    public void test_untracked_files_are_adopted_and_bad_names_left_out() throws Exception {
        write("/seed", "s");
        Files.createDirectories(tree().resolve("copied/in"));
        Files.writeString(tree().resolve("copied/in/x.txt"), "xyz");
        Files.writeString(tree().resolve("bad%name"), "x");
        Files.writeString(tree().resolve("SEED"), "case");
        Files.writeString(meta().resolve(Globals.FILES_TEMP_FOLDER).resolve("leftover.part"), "x");
        restart();
        assertEquals(3, stat("/copied/in/x.txt").size());
        assertTrue(stat("/copied").isDirectory());
        assertEquals(4, store.totals(PROJECT).entries());
        try (var leftovers = Files.list(meta().resolve(Globals.FILES_TEMP_FOLDER))) {
            assertEquals(0, leftovers.count());
        }
    }

    @Test
    public void test_a_symbolic_link_is_never_followed() throws Exception {
        assumeTrue(OwnerOnlyFiles.supported());
        write("/seed", "s");
        final var link = Files.createSymbolicLink(tree().resolve("link"), meta());
        try {
            assertThrows(IOException.class, () -> write("/link/x", "x"));
            restart();
            assertNull(stat("/link"));
        } finally {
            Files.delete(link);
        }
    }

    @Test
    public void test_files_and_folders_are_owner_only() throws Exception {
        assumeTrue(OwnerOnlyFiles.supported());
        write("/private/a", "a");
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(tree().resolve("private/a")));
        assertFalse(Files.getPosixFilePermissions(tree().resolve("private")).contains(PosixFilePermission.OTHERS_READ));
    }

    @Test
    public void test_drop_deletes_the_tree_and_quarantine_moves_it_aside() throws Exception {
        write("/a", "a");
        store.dropProject(PROJECT);
        assertFalse(Files.exists(tree()));
        assertFalse(Files.exists(meta()));
        assertFalse(store.holdsFiles(PROJECT));
        write("/b", "b");
        store.quarantine(PROJECT, "a test");
        assertFalse(Files.exists(tree()));
        try (var siblings = Files.list(store.root())) {
            assertTrue(siblings.anyMatch(
                    path -> String.valueOf(path.getFileName()).startsWith(PROJECT + Globals.QUARANTINE_INFIX)));
        }
    }

    @Test
    public void test_another_life_of_the_project_quarantines_the_old_files() throws Exception {
        store.useIncarnations(_ -> 5L);
        write("/old", "o");
        restart();
        store.useIncarnations(_ -> 9L);
        assertNull(stat("/old"));
        write("/new", "n");
        assertEquals(9L, store.incarnation(PROJECT));
        store.useIncarnations(_ -> 11L);
        assertNull(stat("/new"));
    }

    @Test
    public void test_the_manifest_is_compacted_once_it_grows() throws Exception {
        for (var i = 0; i < Globals.FILE_MANIFEST_COMPACT_FLOOR + 10; i++) {
            write("/same", "v" + i);
        }
        final var lines = Files.readAllLines(meta().resolve(Globals.FILES_MANIFEST_FILE));
        assertTrue(lines.size() < Globals.FILE_MANIFEST_COMPACT_FLOOR, "lines: " + lines.size());
        restart();
        assertNotNull(stat("/same"));
    }

    @Test
    public void test_tombstones_are_kept_only_for_a_cluster_and_collected_by_age() throws Exception {
        final var configuration = Configuration.getInstance();
        configuration.override(ConfigKey.CLUSTER_ENABLED, "true");
        write("/t", "t");
        store.writes(PROJECT).delete(ProjectFilePath.parse("/t"), false, ProjectFilesFixture.nextVersion());
        restart();
        assertEquals(1,
                store.replica(PROJECT).describe().stream().filter(ProjectFileReplica.Described::tombstone).count());
        store.collectTombstones(PROJECT, Long.MAX_VALUE, -1L);
        assertEquals(0, store.replica(PROJECT).describe().size());
        configuration.override(ConfigKey.CLUSTER_ENABLED, "false");
        write("/u", "u");
        store.writes(PROJECT).delete(ProjectFilePath.parse("/u"), false, ProjectFilesFixture.nextVersion());
        restart();
        assertEquals(0, store.replica(PROJECT).describe().size());
    }

    @Test
    public void test_a_damaged_manifest_is_reported_not_treated_as_empty() throws Exception {
        write("/a", "a");
        Files.writeString(meta().resolve(Globals.FILES_MANIFEST_FILE), "junk\n" + ManifestLog.file("/a", 1, 1) + "\n");
        store.createRoot();
        assertThrows(IOException.class, () -> stat("/a"));
    }
}
