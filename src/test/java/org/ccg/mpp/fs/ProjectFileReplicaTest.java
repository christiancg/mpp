package org.ccg.mpp.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.ccg.mpp.data.FileEntry;
import org.ccg.mpp.data.FileKind;
import org.ccg.mpp.data.ProjectFilePath;
import org.ccg.mpp.test.ProjectFilesFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ProjectFileReplicaTest {
    private static final String PROJECT = "replicaproj";
    private ProjectFileStore store;
    private ProjectFileReplica replica;

    @BeforeEach
    void start() throws Exception {
        store = ProjectFilesFixture.start();
        replica = store.replica(PROJECT);
    }

    @AfterEach
    void stop() {
        ProjectFilesFixture.stop();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private FileEntry stat(String path) throws Exception {
        return store.stat(PROJECT, ProjectFilePath.parse(path));
    }

    private String content(String path) throws Exception {
        return Files.readString(store.root().resolve(PROJECT).resolve(path.substring(1)));
    }

    @Test
    public void test_files_folders_and_appends_apply_in_version_order() throws Exception {
        assertTrue(replica.applyDirectory("/d", 10));
        assertTrue(replica.applyFile("/d/f", 11, bytes("abc")));
        assertTrue(replica.applyAppend("/d/f", 12, 11, 3, bytes("def")));
        assertEquals("abcdef", content("/d/f"));
        assertEquals(FileEntry.file(12, 6), stat("/d/f"));
        assertTrue(replica.applyFile("/d/f", 5, bytes("stale")));
        assertEquals("abcdef", content("/d/f"));
        assertTrue(replica.applyDirectory("/d", 9));
        assertEquals(12, replica.localVersion("/d/f"));
    }

    @Test
    public void test_an_append_on_another_base_or_a_missing_parent_is_left_to_anti_entropy() throws Exception {
        assertFalse(replica.applyFile("/missing/f", 1, bytes("x")));
        assertTrue(replica.applyFile("/f", 2, bytes("x")));
        assertFalse(replica.applyAppend("/f", 4, 3, 1, bytes("y")));
        assertFalse(replica.applyAppend("/f", 4, 2, 7, bytes("y")));
        assertFalse(replica.applyAppend("/nothing", 4, 2, 1, bytes("y")));
        assertTrue(replica.applyAppend("/f", 1, 0, 0, bytes("y")));
        assertEquals("x", content("/f"));
    }

    @Test
    public void test_tombstones_delete_and_win_over_older_writes() throws Exception {
        replica.applyDirectory("/d", 1);
        replica.applyFile("/d/f", 2, bytes("x"));
        assertFalse(replica.applyTombstone("/d", 3));
        assertTrue(replica.applyTombstone("/d/f", 3));
        assertTrue(replica.applyTombstone("/d", 4));
        assertFalse(Files.exists(store.root().resolve(PROJECT).resolve("d")));
        assertTrue(replica.applyFile("/d/f", 2, bytes("late")));
        assertNull(stat("/d/f"));
        assertTrue(replica.applyTombstone("/never", 5));
        assertEquals(5, replica.localVersion("/never"));
    }

    @Test
    public void test_a_newer_write_of_another_kind_replaces_the_old_entry() throws Exception {
        replica.applyFile("/x", 1, bytes("file"));
        assertTrue(replica.applyDirectory("/x", 2));
        assertTrue(stat("/x").isDirectory());
        replica.applyFile("/x/child", 3, bytes("c"));
        assertFalse(replica.applyFile("/x", 4, bytes("again")));
        assertTrue(replica.applyFile("/y", 5, bytes("y")));
        assertFalse(replica.applyFile("/Y", 6, bytes("case")));
    }

    @Test
    public void test_moves_apply_when_the_source_matches() throws Exception {
        replica.applyDirectory("/a", 1);
        replica.applyFile("/a/f", 2, bytes("f"));
        assertTrue(replica.applyMove("/a", "/b", 3));
        assertEquals("f", content("/b/f"));
        assertNull(stat("/a"));
        assertEquals(3, stat("/b/f").version());
        assertTrue(replica.applyMove("/a", "/b", 3));
        assertFalse(replica.applyMove("/nothing", "/c", 4));
        assertFalse(replica.applyMove("/b", "/missing/c", 5));
        replica.applyFile("/other", 6, bytes("o"));
        assertFalse(replica.applyMove("/b", "/other", 7));
    }

    @Test
    public void test_describe_and_summary_cover_entries_and_tombstones() throws Exception {
        replica.applyFile("/f", 1, bytes("1"));
        final var before = ProjectFileReplica.summaryOf(replica.describe());
        replica.applyTombstone("/gone", 2);
        final var described = replica.describe();
        assertEquals(List.of("/f", "/gone"), described.stream().map(ProjectFileReplica.Described::path).toList());
        assertTrue(described.getLast().tombstone());
        assertEquals(FileKind.FILE, described.getFirst().kind());
        assertNotEquals(before, ProjectFileReplica.summaryOf(described));
        assertEquals(ProjectFileReplica.summaryOf(described), ProjectFileReplica.summaryOf(replica.describe()));
    }
}
