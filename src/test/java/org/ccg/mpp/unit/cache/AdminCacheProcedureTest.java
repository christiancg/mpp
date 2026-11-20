package org.ccg.mpp.unit.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.ProcedureDefinition;
import org.ccg.mpp.ejson.EJson;
import org.ccg.mpp.ex.MetadataReadException;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class AdminCacheProcedureTest {
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final EJson eJson = IocContainer.get(EJson.class);

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
    void clear() throws Exception {
        for (final var name : fs.metadataFiles().listProcedureNames(TestGlobals.PROJECT)) {
            fs.metadataFiles().deleteProcedure(TestGlobals.PROJECT, name);
        }
        cache.removeProceduresForProject(TestGlobals.PROJECT);
    }

    private ProcedureDefinition write(String name) throws Exception {
        final var definition = new ProcedureDefinition(name, "return " + 1L + ";", 1L, null, true, 1L, 1L, "alice");
        fs.metadataFiles().writeProcedure(TestGlobals.PROJECT, name, eJson.toJson(definition.toJsonObject()));
        return definition;
    }

    @Test
    public void test_get_procedure_loads_lazily_from_disk() throws Exception {
        final var written = write("lazy");
        assertEquals(written, cache.getProcedure(TestGlobals.PROJECT, "lazy"));
    }

    // Proven by writing the file after the miss: the negative entry must still answer null.
    @Test
    public void test_absence_is_negatively_cached() throws Exception {
        assertNull(cache.getProcedure(TestGlobals.PROJECT, "later"));
        write("later");
        assertNull(cache.getProcedure(TestGlobals.PROJECT, "later"));
    }

    @Test
    public void test_put_procedure_overrides_the_cached_value() throws Exception {
        write("p");
        assertEquals(1L, cache.getProcedure(TestGlobals.PROJECT, "p").getVersion());
        final var bumped = new ProcedureDefinition("p", "return 2;", 2L, null, true, 1L, 2L, "bob");
        cache.putProcedure(TestGlobals.PROJECT, bumped);
        assertEquals(2L, cache.getProcedure(TestGlobals.PROJECT, "p").getVersion());
    }

    @Test
    public void test_remove_procedure_forgets_only_that_name() throws Exception {
        write("keep");
        write("drop");
        assertNotNull(cache.getProcedure(TestGlobals.PROJECT, "keep"));
        assertNotNull(cache.getProcedure(TestGlobals.PROJECT, "drop"));
        fs.metadataFiles().deleteProcedure(TestGlobals.PROJECT, "drop");
        cache.removeProcedure(TestGlobals.PROJECT, "drop");
        assertNotNull(cache.getProcedure(TestGlobals.PROJECT, "keep"));
        assertNull(cache.getProcedure(TestGlobals.PROJECT, "drop"));
    }

    @Test
    public void test_remove_procedures_for_project_leaves_other_projects() throws Exception {
        write("p");
        assertNotNull(cache.getProcedure(TestGlobals.PROJECT, "p"));
        cache.removeProceduresForProject("someOtherProject");
        assertNotNull(cache.getProcedure(TestGlobals.PROJECT, "p"));
        cache.removeProceduresForProject(TestGlobals.PROJECT);
        fs.metadataFiles().deleteProcedure(TestGlobals.PROJECT, "p");
        assertNull(cache.getProcedure(TestGlobals.PROJECT, "p"));
    }

    @Test
    public void test_a_malformed_procedure_file_refuses_rather_than_reading_as_absent() throws Exception {
        fs.metadataFiles().writeProcedure(TestGlobals.PROJECT, "broken", "not json at all");

        assertThrows(MetadataReadException.class, () -> cache.getProcedure(TestGlobals.PROJECT, "broken"),
                "conflating a read failure with absence makes an existing procedure answer PROCEDURE_NOT_FOUND");
    }

    @Test
    public void test_a_read_failure_is_not_cached_as_absence() throws Exception {
        fs.metadataFiles().writeProcedure(TestGlobals.PROJECT, "flaky", "not json at all");
        assertThrows(MetadataReadException.class, () -> cache.getProcedure(TestGlobals.PROJECT, "flaky"));

        write("flaky");

        assertNotNull(cache.getProcedure(TestGlobals.PROJECT, "flaky"),
                "a failed read cached as a miss leaves the procedure invisible until eviction or restart");
    }

    @Test
    public void test_a_procedures_folder_that_cannot_be_listed_is_not_cached_as_absence() throws Exception {
        final var folder = new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.PROJECT
                + Globals.FILE_SEPARATOR + Globals.PROCEDURES_FOLDER);
        if (folder.exists()) {
            TestUtils.deleteFolder(folder);
        }
        Files.writeString(folder.toPath(), "x", StandardCharsets.UTF_8);
        try {
            assertThrows(MetadataReadException.class, () -> cache.getProcedure(TestGlobals.PROJECT, "listed"),
                    "a listing failure read as absence made the trigger dispatcher consume every pending run");
        } finally {
            Files.delete(folder.toPath());
        }

        write("listed");

        assertNotNull(cache.getProcedure(TestGlobals.PROJECT, "listed"));
    }
}
