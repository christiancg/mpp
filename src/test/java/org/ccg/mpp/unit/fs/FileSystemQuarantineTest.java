package org.ccg.mpp.unit.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class FileSystemQuarantineTest {
    private FileSystem fileSystem;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.standardTearDown();
    }

    private static File root() {
        return new File(TestGlobals.PATH);
    }

    private static File[] quarantinedIn(File parent, String prefix) {
        final var found = parent.listFiles((_, name) -> name.startsWith(prefix + Globals.QUARANTINE_INFIX));
        assertNotNull(found);
        return found;
    }

    @Test
    public void test_quarantine_of_an_absent_project_reports_nothing_moved() {
        assertFalse(fileSystem.folderQuarantine().moveProjectAside("never_created"));
    }

    @Test
    public void test_project_is_moved_aside_under_the_root() throws Exception {
        fileSystem.createProjectFolder("movedb");
        fileSystem.createCollectionFile("movedb", "c1");

        assertTrue(fileSystem.folderQuarantine().moveProjectAside("movedb"));

        assertFalse(new File(root(), "movedb").exists());
        final var moved = quarantinedIn(root(), "movedb");
        assertEquals(1, moved.length);
        assertTrue(new File(moved[0], "c1").isDirectory());
    }

    @Test
    public void test_two_quarantines_of_one_name_never_collide() {
        fileSystem.createProjectFolder("twicedb");
        assertTrue(fileSystem.folderQuarantine().moveProjectAside("twicedb"));
        fileSystem.createProjectFolder("twicedb");
        assertTrue(fileSystem.folderQuarantine().moveProjectAside("twicedb"));
        fileSystem.createProjectFolder("twicedb");
        assertTrue(fileSystem.folderQuarantine().moveProjectAside("twicedb"));

        assertEquals(3, quarantinedIn(root(), "twicedb").length);
    }

    @Test
    public void test_an_empty_page_zero_is_not_a_leftover() throws Exception {
        fileSystem.createProjectFolder(TestGlobals.PROJECT);
        assertFalse(fileSystem.folderQuarantine().holdsLeftoverCollection(TestGlobals.PROJECT, "fresh"));
        fileSystem.createCollectionFile(TestGlobals.PROJECT, "fresh");

        assertFalse(fileSystem.folderQuarantine().holdsLeftoverCollection(TestGlobals.PROJECT, "fresh"));

        Files.writeString(new File(new File(new File(root(), TestGlobals.PROJECT), "fresh"), "fresh-pk.idx").toPath(),
                "x");
        assertTrue(fileSystem.folderQuarantine().holdsLeftoverCollection(TestGlobals.PROJECT, "fresh"));
    }

    @Test
    public void test_an_empty_project_folder_is_not_a_leftover() throws Exception {
        assertFalse(fileSystem.folderQuarantine().holdsLeftoverProject("emptydb"));
        fileSystem.createProjectFolder("emptydb");

        assertFalse(fileSystem.folderQuarantine().holdsLeftoverProject("emptydb"));

        fileSystem.createCollectionFile("emptydb", "c1");
        assertTrue(fileSystem.folderQuarantine().holdsLeftoverProject("emptydb"));
    }

    @Test
    public void test_startup_scanners_skip_quarantined_folders() throws Exception {
        final var collection = new File(root(), "gonedb" + Globals.QUARANTINE_INFIX + "1" + File.separator + "c1");
        assertTrue(collection.mkdirs());
        Files.writeString(new File(collection, "c1-indexes.dirty").toPath(), "1");
        Files.writeString(new File(collection, "c1-name.building").toPath(), "");

        assertTrue(fileSystem.indexMarkers().retainUncleanStops().isEmpty(),
                "a moved-aside project must not ask the operator to REINDEX a collection that does not exist");
        assertTrue(fileSystem.indexMarkers().listMarked().isEmpty());
    }

    @Test
    public void test_a_re_created_collection_sees_no_stale_tombstones() throws Exception {
        fileSystem.createProjectFolder("tombdb");
        fileSystem.createCollectionFile("tombdb", "c1");
        fileSystem.tombstones().append("tombdb", "c1", "gone", 7L);
        final var file = new File(root(), "tombdb" + File.separator + "c1" + File.separator + "c1-tombstones.idx");
        final var stamp = Files.getLastModifiedTime(file.toPath());
        assertEquals(7L, fileSystem.tombstones().versionOf("tombdb", "c1", "gone"));

        assertTrue(fileSystem.deleteCollectionFiles("tombdb", "c1"));
        fileSystem.createCollectionFile("tombdb", "c1");
        Files.writeString(file.toPath(), "kept" + Globals.ID_SEPARATOR + "7\n");
        Files.setLastModifiedTime(file.toPath(), stamp);

        assertEquals(0L, fileSystem.tombstones().versionOf("tombdb", "c1", "gone"));
        assertEquals(7L, fileSystem.tombstones().versionOf("tombdb", "c1", "kept"));
    }
}
