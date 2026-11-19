package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.admin.AdminProjectEntry;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.SaveOperationHelper;
import org.ccg.mpp.ops.admin.PageOccupancyReconciler;
import org.ccg.mpp.ops.req.SaveRequest;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class CollectionListingRepairTest {
    private static final String DOCUMENT_ID = "a";
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static void killedBetweenRegistrationAndListing() throws Exception {
        AdminOperationHelper.rewriteProjects(project -> {
            if (!TestGlobals.PROJECT.equals(project.get_id())) {
                return null;
            }
            final var listed = new ArrayList<>(project.getCollections());
            listed.remove(TestGlobals.COLL);
            return new AdminProjectEntry(project.get_id(), listed, new ArrayList<>(project.getOwners()));
        }, AdminOperationHelper.RewriteVersion.KEEP);
    }

    private boolean listed() {
        return cache.getAdminProjectEntry(TestGlobals.PROJECT).getCollections().contains(TestGlobals.COLL);
    }

    private void saveOneDocument() throws Exception {
        final var object = new JsonObject();
        object.addProperty(Globals.PK_FIELD, DOCUMENT_ID);
        final var request = new SaveRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        request.setObject(object);
        request.set_id(DOCUMENT_ID);
        assertNotNull(SaveOperationHelper.executeSave(request));
    }

    @Test
    public void test_a_registered_but_unlisted_collection_is_relisted_durably() throws Exception {
        killedBetweenRegistrationAndListing();
        assertFalse(listed());

        AdminOperationHelper.relistUnlistedCollections();

        assertTrue(listed());
        cache.loadAdminData();
        assertTrue(listed(), "the repaired list must reach admin/projects, not only the cache");
    }

    @Test
    public void test_a_project_listing_every_collection_is_not_rewritten() throws Exception {
        final var before = cache.getPkIndexAdminProjectEntry(TestGlobals.PROJECT);

        AdminOperationHelper.relistUnlistedCollections();

        assertSame(before, cache.getPkIndexAdminProjectEntry(TestGlobals.PROJECT));
    }

    @Test
    public void test_dropping_the_project_unregisters_a_relisted_collection() throws Exception {
        killedBetweenRegistrationAndListing();
        AdminOperationHelper.relistUnlistedCollections();

        AdminOperationHelper.deleteProjectEntry(TestGlobals.PROJECT);

        assertNull(cache.getAdminCollectionEntry(TestGlobals.PROJECT, TestGlobals.COLL),
                "an unlisted collection survived DROP_PROJECT and was inherited by the next project of that name");
    }

    @Test
    public void test_reconcile_all_covers_a_registered_collection_its_project_does_not_list() throws Exception {
        saveOneDocument();
        killedBetweenRegistrationAndListing();
        cache.removeAdminPageEntries(TestGlobals.PROJECT, TestGlobals.COLL);

        PageOccupancyReconciler.reconcileAll();

        final var rows = cache.getAdminPageEntries(TestGlobals.PROJECT, TestGlobals.COLL);
        assertNotNull(rows);
        assertEquals(fs.pageFileLengths(TestGlobals.PROJECT, TestGlobals.COLL).get(0L),
                rows.stream().filter(row -> row.getPage() == 0L).findFirst().orElseThrow().getPageSize());
    }
}
