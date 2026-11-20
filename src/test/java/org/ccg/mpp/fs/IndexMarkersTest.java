package org.ccg.mpp.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class IndexMarkersTest {
    private static final String PROJECT = "markerProject";
    private static final String COLL = "markerColl";

    @TempDir
    File tmp;

    private FilePaths paths;
    private IndexMarkers markers;

    @BeforeEach
    public void setUp() {
        paths = new FilePaths();
        paths.useDbPath(tmp.getAbsolutePath());
        assertTrue(paths.collectionFolder(PROJECT, COLL).mkdirs());
        markers = new IndexMarkers(paths);
    }

    @Test
    public void test_a_marked_field_stays_marked_until_cleared() throws IOException {
        markers.mark(PROJECT, COLL, "name");

        assertTrue(markers.isMarked(PROJECT, COLL, "name"));
        assertFalse(markers.isMarked(PROJECT, COLL, "other"));

        markers.clear(PROJECT, COLL, "name");

        assertFalse(markers.isMarked(PROJECT, COLL, "name"));
        assertFalse(new File(paths.collectionFolder(PROJECT, COLL), COLL + "-name.building").exists());
    }

    @Test
    public void test_markers_survive_a_restart() throws IOException {
        markers.mark(PROJECT, COLL, "a.building");
        markers.mark(PROJECT, COLL, "name");

        final var reloaded = new IndexMarkers(paths);

        assertTrue(reloaded.isMarked(PROJECT, COLL, "a.building"));
        assertTrue(reloaded.isMarked(PROJECT, COLL, "name"));
        assertEquals(List.of(PROJECT + "|" + COLL + "|a.building", PROJECT + "|" + COLL + "|name"),
                reloaded.listMarked());
    }

    @Test
    public void test_a_marker_whose_file_vanished_with_its_collection_is_forgotten() throws IOException {
        markers.mark(PROJECT, COLL, "name");
        assertTrue(new File(paths.collectionFolder(PROJECT, COLL), COLL + "-name.building").delete());

        assertFalse(markers.isMarked(PROJECT, COLL, "name"));
        assertTrue(markers.listMarked().isEmpty());
    }

    @Test
    public void test_a_marker_belongs_only_to_its_own_collection_folder() throws IOException {
        final var hyphenated = COLL + "-name";
        assertTrue(paths.collectionFolder(PROJECT, hyphenated).mkdirs());
        markers.mark(PROJECT, COLL, "name");

        final var reloaded = new IndexMarkers(paths);

        assertFalse(reloaded.isMarked(PROJECT, hyphenated, "building"));
        assertTrue(reloaded.isMarked(PROJECT, COLL, "name"));
    }

    @Test
    public void test_an_index_drop_leaves_the_marker_in_place() throws IOException {
        markers.mark(PROJECT, COLL, "name");

        new FieldIndexStore(paths).dropIndex(PROJECT, COLL, "name");

        assertTrue(markers.isMarked(PROJECT, COLL, "name"));
    }

    @Test
    public void test_a_collection_with_no_folder_yet_has_no_files_to_protect() throws IOException {
        markers.mark(PROJECT, "not_written_yet", "name");

        assertFalse(markers.isMarked(PROJECT, "not_written_yet", "name"));
        assertFalse(paths.collectionFolder(PROJECT, "not_written_yet").exists());
    }

    private File dirtyMarker() {
        return new File(paths.collectionFolder(PROJECT, COLL), COLL + "-indexes.dirty");
    }

    @Test
    public void test_clear_dirty_without_a_written_marker_touches_no_file() throws IOException {
        java.nio.file.Files.writeString(dirtyMarker().toPath(), "left by an earlier process");

        markers.clearDirty(PROJECT, COLL);

        assertTrue(dirtyMarker().exists(), "only a marker this process wrote is cleared on the write path");
    }

    @Test
    public void test_a_marker_this_process_wrote_is_cleared() throws IOException {
        markers.markDirty(PROJECT, COLL);
        assertTrue(dirtyMarker().exists());

        markers.clearDirty(PROJECT, COLL);

        assertFalse(dirtyMarker().exists());
        markers.markDirty(PROJECT, COLL);
        markers.clearDirty(PROJECT, COLL);
        assertFalse(dirtyMarker().exists(), "a cleared marker written again is cleared again");
    }

    @Test
    public void test_retire_dirty_removes_a_marker_another_process_left() throws IOException {
        java.nio.file.Files.writeString(dirtyMarker().toPath(), "left by an earlier process");

        markers.retireDirty(PROJECT, COLL);

        assertFalse(dirtyMarker().exists());
    }
}
