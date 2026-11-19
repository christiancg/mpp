package org.ccg.mpp.unit.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class FileSystemProcedureTest {
    private final FileSystem fs = IocContainer.get(FileSystem.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void clearProcedures() throws Exception {
        for (final var name : fs.metadataFiles().listProcedureNames(TestGlobals.PROJECT)) {
            fs.metadataFiles().deleteProcedure(TestGlobals.PROJECT, name);
        }
        fs.metadataFiles().deleteTriggers(TestGlobals.PROJECT, TestGlobals.COLL);
    }

    private static File proceduresFolder() {
        return metadataFolder(Globals.PROCEDURES_FOLDER);
    }

    private static File schedulesFolder() {
        return metadataFolder(Globals.SCHEDULES_FOLDER);
    }

    private static File metadataFolder(String name) {
        return new File(
                TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.PROJECT + Globals.FILE_SEPARATOR + name);
    }

    private static void replaceWithAFile(File folder) throws Exception {
        if (folder.exists()) {
            TestUtils.deleteFolder(folder);
        }
        Files.writeString(folder.toPath(), "x", StandardCharsets.UTF_8);
    }

    @Test
    public void test_read_absent_procedure_returns_null() throws Exception {
        assertNull(fs.metadataFiles().readProcedure(TestGlobals.PROJECT, "nothing"));
    }

    @Test
    public void test_write_then_read_returns_source() throws Exception {
        fs.metadataFiles().writeProcedure(TestGlobals.PROJECT, "one", "{\"name\":\"one\"}");
        assertEquals("{\"name\":\"one\"}", fs.metadataFiles().readProcedure(TestGlobals.PROJECT, "one"));
    }

    @Test
    public void test_write_creates_procedures_folder_on_first_use() throws Exception {
        TestUtils.deleteFolder(proceduresFolder());
        assertFalse(proceduresFolder().exists());
        fs.metadataFiles().writeProcedure(TestGlobals.PROJECT, "one", "{}");
        assertTrue(proceduresFolder().isDirectory());
    }

    @Test
    public void test_overwrite_replaces_the_previous_source() throws Exception {
        fs.metadataFiles().writeProcedure(TestGlobals.PROJECT, "one", "{\"version\":1}");
        fs.metadataFiles().writeProcedure(TestGlobals.PROJECT, "one", "{\"version\":2}");
        assertEquals("{\"version\":2}", fs.metadataFiles().readProcedure(TestGlobals.PROJECT, "one"));
    }

    @Test
    public void test_a_case_variant_procedure_name_reads_as_absent() throws Exception {
        fs.metadataFiles().writeProcedure(TestGlobals.PROJECT, "foo", "{\"name\":\"foo\"}");

        assertNull(fs.metadataFiles().readProcedure(TestGlobals.PROJECT, "Foo"));
        assertFalse(fs.metadataFiles().deleteProcedure(TestGlobals.PROJECT, "Foo"));
        assertEquals("{\"name\":\"foo\"}", fs.metadataFiles().readProcedure(TestGlobals.PROJECT, "foo"));
    }

    @Test
    public void test_a_case_variant_schedule_name_reads_as_absent() throws Exception {
        fs.metadataFiles().writeSchedule(TestGlobals.PROJECT, "daily", "{\"name\":\"daily\"}");
        try {
            assertNull(fs.metadataFiles().readSchedule(TestGlobals.PROJECT, "Daily"));
            assertFalse(fs.metadataFiles().deleteSchedule(TestGlobals.PROJECT, "Daily"));
            assertEquals("{\"name\":\"daily\"}", fs.metadataFiles().readSchedule(TestGlobals.PROJECT, "daily"));
        } finally {
            fs.metadataFiles().deleteSchedule(TestGlobals.PROJECT, "daily");
        }
    }

    @Test
    public void test_a_definition_in_a_missing_folder_reads_as_absent() throws Exception {
        if (proceduresFolder().exists()) {
            TestUtils.deleteFolder(proceduresFolder());
        }

        assertNull(fs.metadataFiles().readProcedure(TestGlobals.PROJECT, "one"));
        assertFalse(fs.metadataFiles().deleteProcedure(TestGlobals.PROJECT, "one"));
    }

    @Test
    public void test_delete_returns_false_when_absent() throws Exception {
        assertFalse(fs.metadataFiles().deleteProcedure(TestGlobals.PROJECT, "nothing"));
    }

    @Test
    public void test_delete_removes_an_existing_procedure() throws Exception {
        fs.metadataFiles().writeProcedure(TestGlobals.PROJECT, "one", "{}");
        assertTrue(fs.metadataFiles().deleteProcedure(TestGlobals.PROJECT, "one"));
        assertNull(fs.metadataFiles().readProcedure(TestGlobals.PROJECT, "one"));
    }

    @Test
    public void test_list_returns_empty_when_folder_absent() throws Exception {
        TestUtils.deleteFolder(proceduresFolder());
        assertTrue(fs.metadataFiles().listProcedureNames(TestGlobals.PROJECT).isEmpty());
    }

    @Test
    public void test_list_strips_extension_and_sorts() throws Exception {
        fs.metadataFiles().writeProcedure(TestGlobals.PROJECT, "second", "{}");
        fs.metadataFiles().writeProcedure(TestGlobals.PROJECT, "first", "{}");
        assertEquals(java.util.List.of("first", "second"), fs.metadataFiles().listProcedureNames(TestGlobals.PROJECT));
    }

    @Test
    public void test_delete_project_removes_procedures_folder() throws Exception {
        fs.createProjectFolder("procdropdb");
        fs.createCollectionFile("procdropdb", "somecoll");
        fs.metadataFiles().writeProcedure("procdropdb", "one", "{}");
        assertTrue(fs.deleteProject("procdropdb"));
        assertFalse(new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + "procdropdb").exists());
    }

    @Test
    public void test_read_absent_triggers_returns_null() throws Exception {
        assertNull(fs.metadataFiles().readTriggers(TestGlobals.PROJECT, TestGlobals.COLL));
    }

    @Test
    public void test_write_then_read_triggers() throws Exception {
        fs.metadataFiles().writeTriggers(TestGlobals.PROJECT, TestGlobals.COLL, "[{\"name\":\"t\"}]");
        assertEquals("[{\"name\":\"t\"}]", fs.metadataFiles().readTriggers(TestGlobals.PROJECT, TestGlobals.COLL));
    }

    @Test
    public void test_delete_triggers_reports_whether_a_file_was_removed() throws Exception {
        assertFalse(fs.metadataFiles().deleteTriggers(TestGlobals.PROJECT, TestGlobals.COLL));
        fs.metadataFiles().writeTriggers(TestGlobals.PROJECT, TestGlobals.COLL, "[]");
        assertTrue(fs.metadataFiles().deleteTriggers(TestGlobals.PROJECT, TestGlobals.COLL));
    }

    @Test
    public void test_delete_collection_files_removes_triggers_file() throws Exception {
        fs.createCollectionFile(TestGlobals.PROJECT, "trigdropcoll");
        fs.metadataFiles().writeTriggers(TestGlobals.PROJECT, "trigdropcoll", "[{\"name\":\"t\"}]");
        assertTrue(fs.deleteCollectionFiles(TestGlobals.PROJECT, "trigdropcoll"));
        assertNull(fs.metadataFiles().readTriggers(TestGlobals.PROJECT, "trigdropcoll"));
    }

    @Test
    public void test_a_procedures_folder_that_cannot_be_listed_fails_instead_of_reading_absent() throws Exception {
        replaceWithAFile(proceduresFolder());
        try {
            assertThrows(IOException.class, () -> fs.metadataFiles().readProcedure(TestGlobals.PROJECT, "one"));
            assertThrows(IOException.class, () -> fs.metadataFiles().deleteProcedure(TestGlobals.PROJECT, "one"));
        } finally {
            Files.delete(proceduresFolder().toPath());
        }
    }

    @Test
    public void test_a_schedules_folder_that_cannot_be_listed_fails_instead_of_reading_absent() throws Exception {
        replaceWithAFile(schedulesFolder());
        try {
            assertThrows(IOException.class, () -> fs.metadataFiles().readSchedule(TestGlobals.PROJECT, "daily"));
            assertThrows(IOException.class, () -> fs.metadataFiles().deleteSchedule(TestGlobals.PROJECT, "daily"));
        } finally {
            Files.delete(schedulesFolder().toPath());
        }
    }
}
