package org.ccg.mpp.unit.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.DbEntry;
import org.ccg.mpp.data.PkIndexEntry;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.test.TestGlobals;
import org.junit.jupiter.api.Test;

public class FileSystemDeleteTest extends FileSystemTestBase {
    @Test
    public void test_delete_existing_project() {
        final var fileSystem = fs;

        boolean result = fileSystem.deleteProject(TestGlobals.PROJECT);

        assertTrue(result);
    }

    @Test
    public void test_delete_absent_project_counts_as_deleted() {
        final var fileSystem = fs;

        boolean result = fileSystem.deleteProject("nonExistentProject");

        assertTrue(result);
    }

    @Test
    public void test_delete_collection_files_success() {
        final var fileSystem = fs;

        boolean result = fileSystem.deleteCollectionFiles(TestGlobals.PROJECT, TestGlobals.COLL);

        assertTrue(result);
    }

    @Test
    public void test_delete_absent_collection_folder_counts_as_deleted() {
        FileSystem fileSystem = new FileSystem();
        String projectName = "nonExistentProject";
        String collectionName = "nonExistentCollection";

        boolean result = fileSystem.deleteCollectionFiles(projectName, collectionName);

        assertTrue(result);
    }

    @Test
    public void test_delete_entry_leaves_a_hole_and_keeps_the_rest_in_place() throws IOException {
        final var fileSystem = fs;

        final var file = new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.PROJECT
                + Globals.FILE_SEPARATOR + TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL
                + Globals.FILE_PAGE_SEPARATOR + "0.dat");

        JsonObject data = new JsonObject();
        data.addProperty("name", "test");

        DbEntry entry1 = DbEntry.fromJsonObject(TestGlobals.PROJECT, TestGlobals.COLL, data);
        entry1.set_id("1");
        DbEntry entry2 = DbEntry.fromJsonObject(TestGlobals.PROJECT, TestGlobals.COLL, data);
        entry2.set_id("2");

        final PkIndexEntry entryToDelete = fileSystem.insertIntoCollection(entry1);
        fileSystem.insertIntoCollection(entry2);

        fileSystem.deleteFromCollection(entryToDelete);

        try (RandomAccessFile reader = new RandomAccessFile(file, Globals.RW_PERMISSIONS)) {
            byte[] content = new byte[(int) reader.length()];
            reader.readFully(content);
            final var hole = " ".repeat((int) entryToDelete.getLength() - Globals.NEWLINE.length());
            assertEquals(hole + Globals.NEWLINE + entry2.toFileEntry() + Globals.NEWLINE,
                    new String(content, StandardCharsets.UTF_8));
        }

        assertTrue(file.delete());
    }
}
