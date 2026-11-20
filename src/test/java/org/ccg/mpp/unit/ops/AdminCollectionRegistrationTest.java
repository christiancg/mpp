package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.admin.AdminCollEntry;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class AdminCollectionRegistrationTest {
    private static final String NEW_COLL = "freshCollection";

    private Cache cache;
    private File[] projectPages;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
        cache = IocContainer.get(Cache.class);
        final var fs = IocContainer.get(FileSystem.class);
        final var folder = new File(TestUtils.getDbPath(fs),
                Globals.ADMIN_PROJECT_NAME + File.separator + Globals.ADMIN_PROJECTS_COLLECTION_NAME);
        projectPages = folder.listFiles((_, name) -> name.endsWith(".dat"));
        Assumptions.assumeTrue(projectPages != null && projectPages.length > 0);
    }

    @AfterEach
    public void tearDown() throws Exception {
        for (final var page : projectPages) {
            assertTrue(page.setWritable(true));
        }
        TestUtils.standardTearDown();
    }

    private void blockProjectRowWrites() {
        for (final var page : projectPages) {
            assertTrue(page.setWritable(false));
        }
        Assumptions.assumeFalse(projectPages[0].canWrite());
    }

    @Test
    public void test_a_failed_project_row_write_leaves_a_new_collection_unregistered_and_unlisted() {
        final var listedBefore = new ArrayList<>(cache.getAdminProjectEntry(TestGlobals.PROJECT).getCollections());
        blockProjectRowWrites();

        assertThrows(Exception.class,
                () -> AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.PROJECT, NEW_COLL)));

        assertEquals(listedBefore, cache.getAdminProjectEntry(TestGlobals.PROJECT).getCollections());
        assertNull(cache.getAdminCollectionEntry(TestGlobals.PROJECT, NEW_COLL));
        assertNull(cache.getPkIndexAdminCollEntry(Cache.getCollectionIdentifier(TestGlobals.PROJECT, NEW_COLL)));
    }

    @Test
    public void test_a_failed_project_row_write_keeps_a_dropped_collection_listed_in_memory() {
        blockProjectRowWrites();

        assertThrows(Exception.class,
                () -> AdminOperationHelper.deleteCollectionEntry(TestGlobals.PROJECT, TestGlobals.COLL));

        assertTrue(cache.getAdminProjectEntry(TestGlobals.PROJECT).getCollections().contains(TestGlobals.COLL));
    }

    @Test
    public void test_a_successful_registration_lists_the_collection_on_a_fresh_entry() throws Exception {
        final var before = cache.getAdminProjectEntry(TestGlobals.PROJECT);
        final var listedBefore = new ArrayList<>(before.getCollections());

        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.PROJECT, NEW_COLL));

        assertEquals(listedBefore, before.getCollections());
        final var expected = new ArrayList<>(listedBefore);
        expected.add(NEW_COLL);
        assertEquals(expected, cache.getAdminProjectEntry(TestGlobals.PROJECT).getCollections());
        assertNotNull(cache.getAdminCollectionEntry(TestGlobals.PROJECT, NEW_COLL));
    }

    @Test
    public void test_a_successful_drop_unlists_the_collection() throws Exception {
        AdminOperationHelper.deleteCollectionEntry(TestGlobals.PROJECT, TestGlobals.COLL);

        assertEquals(List.of(), cache.getAdminProjectEntry(TestGlobals.PROJECT).getCollections());
        assertNull(cache.getAdminCollectionEntry(TestGlobals.PROJECT, TestGlobals.COLL));
    }
}
