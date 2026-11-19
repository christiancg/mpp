package org.ccg.mpp.unit.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.DbEntry;
import org.ccg.mpp.data.IndexedDbEntry;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ex.DirectoryNotFoundException;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class FileSystemWriteTest extends FileSystemTestBase {
    @Test
    public void test_create_base_db_path_success() {
        FileSystem fs = new FileSystem();
        fs.createBaseDbPath();

        File dbDir = new File(TestGlobals.PATH);
        assertTrue(dbDir.exists());
        assertTrue(dbDir.isDirectory());
        assertTrue(dbDir.canRead());
        assertTrue(dbDir.canWrite());
    }

    @Test
    public void test_create_base_db_path_invalid_path(@TempDir Path tmp) throws Exception {
        final var notAFolder = Files.createFile(tmp.resolve("not-a-folder"));
        FileSystem fs = new FileSystem();
        Configuration config = Configuration.getInstance();
        TestUtils.setPrivateField(config, "filePath", notAFolder.resolve("cannot").resolve("be-created").toString());

        assertThrows(DirectoryNotFoundException.class, fs::createBaseDbPath);
    }

    @Test
    public void test_creates_admin_project_successfully() throws IOException {
        FileSystem fileSystem = new FileSystem();
        fileSystem.createBaseDbPath();
        fileSystem.createAdminProject();

        File adminProjectFolder = new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + Globals.ADMIN_PROJECT_NAME);
        assertTrue(adminProjectFolder.exists());

        File projectsCollection = new File(
                adminProjectFolder.getPath() + Globals.FILE_SEPARATOR + Globals.ADMIN_PROJECTS_COLLECTION_NAME);
        assertTrue(projectsCollection.exists());

        File collectionsCollection = new File(
                adminProjectFolder.getPath() + Globals.FILE_SEPARATOR + Globals.ADMIN_COLLECTIONS_COLLECTION_NAME);
        assertTrue(collectionsCollection.exists());
    }

    @Test
    public void test_creates_new_project_folder_successfully() throws Exception {
        String testDbPath = System.getProperty("java.io.tmpdir");
        FileSystem fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, testDbPath);

        boolean result = fileSystem.createProjectFolder(TestGlobals.PROJECT);

        assertTrue(result);
        File projectFolder = new File(testDbPath + Globals.FILE_SEPARATOR + TestGlobals.PROJECT);
        assertTrue(projectFolder.exists());
        assertTrue(projectFolder.delete());
    }

    @Test
    public void test_bulk_insert_multiple_entries_success() throws IOException {
        fs.createBaseDbPath();
        fs.createAdminProject();
        fs.createProjectFolder(TestGlobals.PROJECT);
        fs.createCollectionFile(TestGlobals.PROJECT, TestGlobals.COLL);

        List<DbEntry> entries = new ArrayList<>();
        JsonObject data1 = new JsonObject();
        data1.addProperty("field1", "value1");
        DbEntry entry1 = DbEntry.fromJsonObject(TestGlobals.PROJECT, TestGlobals.COLL, data1);

        JsonObject data2 = new JsonObject();
        data2.addProperty("field2", "value2");
        DbEntry entry2 = DbEntry.fromJsonObject(TestGlobals.PROJECT, TestGlobals.COLL, data2);

        entries.add(entry1);
        entries.add(entry2);

        List<IndexedDbEntry> result = fs.bulkInsertIntoCollection(TestGlobals.PROJECT, TestGlobals.COLL, entries);

        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals(TestGlobals.PROJECT, result.getFirst().getProjectName());
        assertEquals(TestGlobals.COLL, result.getFirst().getCollectionName());
        assertNotNull(result.getFirst().get_id());
        assertNotNull(result.getFirst().getIndex());
    }

    @Test
    public void test_bulk_insert_groups_entries_by_page() throws IOException {
        fs.createBaseDbPath();
        fs.createAdminProject();
        fs.createProjectFolder(TestGlobals.PROJECT);
        fs.createCollectionFile(TestGlobals.PROJECT, TestGlobals.COLL);

        List<DbEntry> entries = new ArrayList<>();
        JsonObject d1 = new JsonObject();
        d1.addProperty("f", "p0");
        DbEntry e1 = DbEntry.fromJsonObject(TestGlobals.PROJECT, TestGlobals.COLL, d1);
        e1.setPage(0);
        e1.set_id("p0-1");

        JsonObject d2 = new JsonObject();
        d2.addProperty("f", "p1");
        DbEntry e2 = DbEntry.fromJsonObject(TestGlobals.PROJECT, TestGlobals.COLL, d2);
        e2.setPage(1);
        e2.set_id("p1-1");

        entries.add(e1);
        entries.add(e2);

        List<IndexedDbEntry> result = fs.bulkInsertIntoCollection(TestGlobals.PROJECT, TestGlobals.COLL, entries);
        assertEquals(2, result.size());

        final var page0 = fs.readWholeCollectionPage(TestGlobals.PROJECT, TestGlobals.COLL, 0);
        final var page1 = fs.readWholeCollectionPage(TestGlobals.PROJECT, TestGlobals.COLL, 1);
        assertEquals(1, page0.size(), "Page 0 should contain only its grouped entry");
        assertEquals(1, page1.size(), "Page 1 should contain only its grouped entry");
        assertTrue(page0.containsKey("p0-1"));
        assertTrue(page1.containsKey("p1-1"));
    }

    @Test
    public void test_bulk_insert_empty_list() throws IOException {

        List<DbEntry> entries = new ArrayList<>();

        List<IndexedDbEntry> result = fs.bulkInsertIntoCollection(TestGlobals.PROJECT, TestGlobals.COLL, entries);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    public void test_bulk_update_multiple_entries_success() throws IOException {
        final var fileSystem = fs;

        final var data1 = new JsonObject();
        data1.addProperty("_id", "1");
        data1.addProperty("field", "value");
        final var data2 = new JsonObject();
        data1.addProperty("_id", "2");
        data2.addProperty("field", "value");

        final var newEntries = List.of(DbEntry.fromJsonObject(TestGlobals.PROJECT, TestGlobals.COLL, data1),
                DbEntry.fromJsonObject(TestGlobals.PROJECT, TestGlobals.COLL, data2));
        List<IndexedDbEntry> entries = fileSystem.bulkInsertIntoCollection(TestGlobals.PROJECT, TestGlobals.COLL,
                newEntries);

        final var first = entries.get(0);
        first.getData().addProperty("field", "changed");
        final var second = entries.get(1);
        second.getData().addProperty("field", "changed");

        final var result = fileSystem.bulkUpdateFromCollection(TestGlobals.PROJECT, TestGlobals.COLL, entries);

        assertNotNull(result);
        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(e -> e.getData().get("field").asJsonString().getValue().equals("changed")));
        assertEquals(TestGlobals.PROJECT, result.getFirst().getProjectName());
        assertEquals(TestGlobals.COLL, result.getFirst().getCollectionName());
    }

    @Test
    public void test_bulk_update_preserves_the_write_version() throws IOException {
        final var fileSystem = fs;
        final var data = new JsonObject();
        data.addProperty("_id", "v1");
        data.addProperty("field", "value");
        final var seed = DbEntry.fromJsonObject(TestGlobals.PROJECT, TestGlobals.COLL, data);
        seed.setVersion(11L);
        final var inserted = fileSystem.bulkInsertIntoCollection(TestGlobals.PROJECT, TestGlobals.COLL, List.of(seed));
        final var updated = inserted.getFirst();
        updated.getData().addProperty("field", "changed");
        updated.setVersion(22L);

        final var result = fileSystem.bulkUpdateFromCollection(TestGlobals.PROJECT, TestGlobals.COLL, List.of(updated));

        assertEquals(22L, result.getFirst().getIndex().getVersion());
        assertEquals(22L, result.getFirst().toDbEntry().getVersion(),
                "the published document must not differ from the pk index that describes it");
        assertEquals(22L,
                fileSystem.readWholePkIndexFile(TestGlobals.PROJECT, TestGlobals.COLL).getFirst().getVersion());
    }

    @Test
    public void test_bulk_update_empty_entries_list() throws IOException {
        final var fileSystem = fs;

        List<IndexedDbEntry> entries = new ArrayList<>();

        final var result = fileSystem.bulkUpdateFromCollection(TestGlobals.PROJECT, TestGlobals.COLL, entries);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    public void test_bulk_update_multiple_same_page_entries_no_corruption() throws Exception {
        final var fileSystem = fs;
        final var toInsert = new ArrayList<DbEntry>();
        for (var id : List.of("1", "2", "3")) {
            final var data = new JsonObject();
            data.addProperty("_id", id);
            data.addProperty("field", "orig-" + id);
            toInsert.add(DbEntry.fromJsonObject(TestGlobals.PROJECT, TestGlobals.COLL, data));
        }
        final var inserted = fileSystem.bulkInsertIntoCollection(TestGlobals.PROJECT, TestGlobals.COLL, toInsert);
        // All three live on page 0; change each value to a DIFFERENT-length string to force shifts.
        for (var ie : inserted) {
            ie.getData().addProperty("field", "updated-value-for-" + ie.get_id() + "-longer");
        }

        final var result = fileSystem.bulkUpdateFromCollection(TestGlobals.PROJECT, TestGlobals.COLL, inserted);

        for (var ie : result) {
            final var read = fileSystem.getById(ie.getIndex());
            assertEquals("updated-value-for-" + ie.get_id() + "-longer",
                    read.getData().get("field").asJsonString().getValue(),
                    "doc " + ie.get_id() + " must read back intact after a multi-same-page bulk update");
        }
    }

    @Test
    public void test_update_file_length() throws IOException {
        final var fileSystem = fs;
        final var file = new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.PROJECT
                + Globals.FILE_SEPARATOR + TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL
                + Globals.FILE_PAGE_SEPARATOR + "0.dat");
        final var jsonObject = new JsonObject();
        jsonObject.addProperty("name", "test");
        final var dbEntry = DbEntry.fromJsonObject(TestGlobals.PROJECT, TestGlobals.COLL, jsonObject);
        final var inserted = fileSystem.insertIntoCollection(dbEntry);
        assertTrue(file.length() > 0);
        final var originalFileLength = file.length();
        jsonObject.addProperty("name", "updated");
        final var dbEntryUpdated = DbEntry.fromJsonObject(TestGlobals.PROJECT, TestGlobals.COLL, jsonObject);
        fileSystem.updateFromCollection(dbEntryUpdated, inserted);
        assertTrue(file.length() > originalFileLength);
    }

    @Test
    public void test_concurrent_inserts_and_reads_keep_page_coherent() throws Exception {
        final var fileSystem = fs;
        final int writers = 8;
        final int perWriter = 25;
        final var errors = new java.util.concurrent.CopyOnWriteArrayList<Throwable>();
        final var threads = new ArrayList<Thread>();
        for (int w = 0; w < writers; w++) {
            final int base = w * perWriter;
            threads.add(new Thread(() -> {
                for (int i = 0; i < perWriter; i++) {
                    try {
                        final var obj = new JsonObject();
                        obj.addProperty("_id", "c-" + (base + i));
                        obj.addProperty("v", base + i);
                        final var entry = DbEntry.fromJsonObject(TestGlobals.PROJECT, TestGlobals.COLL, obj);
                        entry.setPage(0);
                        fileSystem.insertIntoCollection(entry);
                        fileSystem.readWholeCollectionPage(TestGlobals.PROJECT, TestGlobals.COLL, 0);
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                }
            }));
        }
        for (var t : threads)
            t.start();
        for (var t : threads)
            t.join(15000);
        assertTrue(errors.isEmpty(), "no errors expected under concurrent insert/read: " + errors);
        final var page = fileSystem.readWholeCollectionPage(TestGlobals.PROJECT, TestGlobals.COLL, 0);
        assertEquals(writers * perWriter, page.size());
    }

    @Test
    public void test_bulk_update_mid_batch_failure_commits_nothing() throws Exception {
        FileSystem fs = FileSystemTestBase.freshFs();
        final var idxA = FileSystemTestBase.insertOnPage(fs, "a", 0);
        final var idxB = FileSystemTestBase.insertOnPage(fs, "b", 1);
        final var updates = List.of(FileSystemTestBase.updateEntry(idxA, "a", "updated-value-for-a"),
                FileSystemTestBase.updateEntry(idxB, "b", "updated-value-for-b"));

        final var pageBFile = new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.PROJECT
                + Globals.FILE_SEPARATOR + TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL
                + Globals.FILE_PAGE_SEPARATOR + "1" + Globals.DB_FILE_EXTENSION);
        assertTrue(pageBFile.delete());
        assertTrue(pageBFile.mkdir(), "page b's path must be unwritable for this test to inject a failure");

        assertThrows(IOException.class,
                () -> fs.bulkUpdateFromCollection(TestGlobals.PROJECT, TestGlobals.COLL, updates));

        assertEquals("short", FileSystemTestBase.readValueFromDisk(fs, "a"),
                "the copy appended for the entry before the failing one is cut back");
    }

    @Test
    public void test_bulk_update_multi_page_reads_back_intact() throws Exception {
        FileSystem fs = FileSystemTestBase.freshFs();
        final var idxA = FileSystemTestBase.insertOnPage(fs, "a", 0);
        final var idxB = FileSystemTestBase.insertOnPage(fs, "b", 1);
        final var idxC = FileSystemTestBase.insertOnPage(fs, "c", 2);
        final var updates = List.of(FileSystemTestBase.updateEntry(idxA, "a", "updated-longer-value-for-a"),
                FileSystemTestBase.updateEntry(idxB, "b", "updated-longer-value-for-b"),
                FileSystemTestBase.updateEntry(idxC, "c", "updated-longer-value-for-c"));

        fs.bulkUpdateFromCollection(TestGlobals.PROJECT, TestGlobals.COLL, updates);

        assertEquals("updated-longer-value-for-a", FileSystemTestBase.readValueFromDisk(fs, "a"));
        assertEquals("updated-longer-value-for-b", FileSystemTestBase.readValueFromDisk(fs, "b"));
        assertEquals("updated-longer-value-for-c", FileSystemTestBase.readValueFromDisk(fs, "c"));
    }

    @Test
    public void test_bulk_update_same_page_reads_back_intact() throws Exception {
        FileSystem fs = FileSystemTestBase.freshFs();
        final var idxA = FileSystemTestBase.insertOnPage(fs, "a", 0);
        final var idxB = FileSystemTestBase.insertOnPage(fs, "b", 0);
        final var idxC = FileSystemTestBase.insertOnPage(fs, "c", 0);
        final var updates = List.of(FileSystemTestBase.updateEntry(idxA, "a", "updated-longer-value-for-a"),
                FileSystemTestBase.updateEntry(idxB, "b", "updated-longer-value-for-b"),
                FileSystemTestBase.updateEntry(idxC, "c", "updated-longer-value-for-c"));

        fs.bulkUpdateFromCollection(TestGlobals.PROJECT, TestGlobals.COLL, updates);

        assertEquals("updated-longer-value-for-a", FileSystemTestBase.readValueFromDisk(fs, "a"));
        assertEquals("updated-longer-value-for-b", FileSystemTestBase.readValueFromDisk(fs, "b"));
        assertEquals("updated-longer-value-for-c", FileSystemTestBase.readValueFromDisk(fs, "c"));
    }

    private static File pageFile() {
        return new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.PROJECT + Globals.FILE_SEPARATOR
                + TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL + Globals.FILE_PAGE_SEPARATOR + "0.dat");
    }

    private static DbEntry entry(String id) {
        final var object = new JsonObject();
        object.addProperty("_id", id);
        object.addProperty("name", "value");
        return DbEntry.fromJsonObject(TestGlobals.PROJECT, TestGlobals.COLL, object);
    }

    @Test
    public void test_a_failed_index_write_truncates_the_page() throws Exception {
        final var fileSystem = fs;
        fileSystem.insertIntoCollection(entry("kept"));
        final var lengthBefore = pageFile().length();
        final var folder = pageFile().getParentFile();
        final var indexFile = new File(folder, TestGlobals.COLL + Globals.INDEX_FILE_NAME_SEPARATOR
                + Globals.PK_INDEX_FILE_NAME + Globals.INDEX_FILE_EXTENSION);
        assertTrue(indexFile.delete() || !indexFile.exists());
        assertTrue(indexFile.mkdir(), "the index path must be unwritable for this test to inject a failure");

        assertThrows(IOException.class, () -> fileSystem.insertIntoCollection(entry("orphan")));

        assertEquals(lengthBefore, pageFile().length(),
                "a document whose index entry could not be written must not stay in the page: full scans would"
                        + " return it while FIND_BY_ID says it does not exist");
    }

    @Test
    public void test_quarantine_moves_the_collection_folder_aside() throws Exception {
        final var fileSystem = fs;
        fileSystem.createProjectFolder(TestGlobals.PROJECT);
        fileSystem.createCollectionFile(TestGlobals.PROJECT, TestGlobals.COLL);
        final var data = new JsonObject();
        data.addProperty("pad", "x");
        final var entry = DbEntry.fromJsonObject(TestGlobals.PROJECT, TestGlobals.COLL, data);
        entry.set_id("kept");
        fileSystem.insertIntoCollection(entry);

        assertTrue(fileSystem.folderQuarantine().moveCollectionAside(TestGlobals.PROJECT, TestGlobals.COLL, 42L));

        final var projectFolder = new File(TestGlobals.PATH + File.separator + TestGlobals.PROJECT);
        final var moved = projectFolder.listFiles((_, name) -> name.startsWith(TestGlobals.COLL + ".quarantined-42-"));
        assertNotNull(moved);
        assertEquals(1, moved.length, "the documents are moved aside, never deleted");
        assertTrue(Objects.requireNonNull(moved[0].listFiles()).length > 0);
        assertFalse(new File(projectFolder, TestGlobals.COLL).exists(),
                "the live path is cleared so the collection can be re-created empty at the new incarnation");
    }

    @Test
    public void test_quarantine_of_a_collection_with_no_folder_reports_nothing_moved() {
        final var fileSystem = fs;
        fileSystem.createProjectFolder(TestGlobals.PROJECT);

        assertFalse(fileSystem.folderQuarantine().moveCollectionAside(TestGlobals.PROJECT, "never_created", 7L));
    }
}
