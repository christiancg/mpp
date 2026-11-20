package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mockStatic;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.ccg.mpp.bckg_ops.PendingIndexWrites;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.req.AggregateRequest;
import org.ccg.mpp.ops.req.CreateCollectionRequest;
import org.ccg.mpp.ops.req.CreateProjectRequest;
import org.ccg.mpp.ops.req.DropCollectionRequest;
import org.ccg.mpp.ops.req.DropProjectRequest;
import org.ccg.mpp.ops.req.FindByIdRequest;
import org.ccg.mpp.ops.req.SaveRequest;
import org.ccg.mpp.ops.req.agg.step.CountAggregationStep;
import org.ccg.mpp.ops.resp.FindByIdResponse;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class DropRecreateProjectTest {
    private static final String STRAY_COLL = "strayCollection";
    private static final String STRAY_PROJECT = "strayProject";
    private static final String DELETE_COLLECTION_ENTRY = "deleteCollectionEntry";

    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final PendingIndexWrites pending = IocContainer.get(PendingIndexWrites.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
    }

    @AfterEach
    public void tearDown() throws Exception {
        final var strayProject = projectFolder(STRAY_PROJECT);
        if (strayProject.isDirectory()) {
            TestUtils.deleteFolder(strayProject);
        }
        pending.clearProject(TestGlobals.PROJECT);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void recreateCollection() {
        processor.processMessage(new CreateProjectRequest(TestGlobals.PROJECT));
        assertEquals(OperationStatus.OK, processor
                .processMessage(new CreateCollectionRequest(TestGlobals.PROJECT, TestGlobals.COLL)).getStatus());
    }

    private void save(String value) {
        final var request = new SaveRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        final var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString("shared"));
        obj.addProperty("value", value);
        request.setObject(obj);
        request.set_id("shared");
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
    }

    private void count() {
        final var request = new AggregateRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        request.setAggregationSteps(List.of(new CountAggregationStep()));
        processor.processMessage(request);
    }

    private String valueOf() {
        final var request = new FindByIdRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        request.set_id("shared");
        final var response = processor.processMessage(request);
        assertEquals(OperationStatus.OK, response.getStatus());
        assertInstanceOf(FindByIdResponse.class, response);
        return ((FindByIdResponse) response).getObject().get("value").asJsonString().getValue();
    }

    private static File collectionFolder(String projectName, String collName) {
        return new File(new File(TestGlobals.PATH, projectName), collName);
    }

    private static File projectFolder(String projectName) {
        return new File(TestGlobals.PATH, projectName);
    }

    private static File createStrayFolder(File folder) {
        assertTrue(folder.mkdirs(), "the fixture could not create " + folder);
        return folder;
    }

    private OperationStatus dropCollection(String projectName, String collName) {
        return processor.processMessage(new DropCollectionRequest(projectName, collName)).getStatus();
    }

    private OperationStatus dropProject(String projectName) {
        return processor.processMessage(new DropProjectRequest(projectName)).getStatus();
    }

    @Test
    public void test_recreated_collection_does_not_inherit_the_dropped_pk_index() {
        save("before");
        count();
        // The state evictProject used to leave behind: the pk index is cached with no documents
        // beside it, which is what memory pressure and every index-only read produce.
        cache.userCache().evictCollectionDocuments(TestGlobals.PROJECT, TestGlobals.COLL);

        assertEquals(OperationStatus.OK,
                processor.processMessage(new DropProjectRequest(TestGlobals.PROJECT)).getStatus());
        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateProjectRequest(TestGlobals.PROJECT)).getStatus());
        assertEquals(OperationStatus.OK, processor
                .processMessage(new CreateCollectionRequest(TestGlobals.PROJECT, TestGlobals.COLL)).getStatus());
        save("after");

        assertEquals("after", valueOf());
    }

    @Test
    public void test_case_differing_project_is_refused() {
        final var colliding = processor
                .processMessage(new CreateProjectRequest(TestGlobals.PROJECT.toUpperCase(Locale.ROOT)));
        assertEquals(ErrorCode.NAME_COLLIDES_ON_DISK.getCode(), colliding.getErrorCode());
    }

    @Test
    public void shouldLeaveAnUnregisteredCollectionFolderOnDisk() {
        final var stray = createStrayFolder(collectionFolder(TestGlobals.PROJECT, STRAY_COLL));
        assertEquals(OperationStatus.ERROR, dropCollection(TestGlobals.PROJECT, STRAY_COLL));
        assertTrue(stray.isDirectory(), "an unregistered collection folder must not be deleted");
    }

    @Test
    public void shouldLeaveAnUnregisteredProjectFolderOnDisk() {
        final var stray = createStrayFolder(projectFolder(STRAY_PROJECT));
        assertEquals(OperationStatus.ERROR, dropProject(STRAY_PROJECT));
        assertTrue(stray.isDirectory(), "an unregistered project folder must not be deleted");
    }

    @Test
    public void shouldLeaveACollectionFolderOnDiskWhenItsProjectIsNotRegistered() {
        final var stray = createStrayFolder(collectionFolder(STRAY_PROJECT, STRAY_COLL));
        assertEquals(OperationStatus.ERROR, dropCollection(STRAY_PROJECT, STRAY_COLL));
        assertTrue(stray.isDirectory(), "an unregistered collection folder must not be deleted");
    }

    @Test
    public void shouldStillDropARegisteredCollection() {
        final var registered = collectionFolder(TestGlobals.PROJECT, TestGlobals.COLL);
        assertTrue(registered.isDirectory());
        assertEquals(OperationStatus.OK, dropCollection(TestGlobals.PROJECT, TestGlobals.COLL));
        assertFalse(registered.exists(), "a registered collection must still be removed");
    }

    @Test
    public void shouldStillDropARegisteredProject() {
        final var registered = projectFolder(TestGlobals.PROJECT);
        assertTrue(registered.isDirectory());
        assertEquals(OperationStatus.OK, dropProject(TestGlobals.PROJECT));
        assertFalse(registered.exists(), "a registered project must still be removed");
    }

    @Test
    public void shouldRefuseASecondDropOfTheSameCollection() {
        assertEquals(OperationStatus.OK, dropCollection(TestGlobals.PROJECT, TestGlobals.COLL));
        assertEquals(OperationStatus.ERROR, dropCollection(TestGlobals.PROJECT, TestGlobals.COLL));
    }

    @Test
    public void shouldRefuseASecondDropOfTheSameProject() {
        assertEquals(OperationStatus.OK, dropProject(TestGlobals.PROJECT));
        assertEquals(OperationStatus.ERROR, dropProject(TestGlobals.PROJECT));
    }

    @Test
    public void shouldLeaveTheRegisteredSiblingIntactWhenAStrayFolderIsDropped() {
        createStrayFolder(collectionFolder(TestGlobals.PROJECT, STRAY_COLL));
        assertEquals(OperationStatus.ERROR, dropCollection(TestGlobals.PROJECT, STRAY_COLL));
        assertTrue(collectionFolder(TestGlobals.PROJECT, TestGlobals.COLL).isDirectory(),
                "dropping an unregistered name must not touch a registered collection");
    }

    @Test
    public void drop_collection_of_a_registered_name_with_no_folder_unregisters_it() {
        TestUtils.deleteFolder(collectionFolder(TestGlobals.PROJECT, TestGlobals.COLL));

        assertEquals(OperationStatus.OK, dropCollection(TestGlobals.PROJECT, TestGlobals.COLL));
        assertNull(cache.getAdminCollectionEntry(TestGlobals.PROJECT, TestGlobals.COLL),
                "the collection must no longer be registered");
        assertEquals(OperationStatus.OK, processor
                .processMessage(new CreateCollectionRequest(TestGlobals.PROJECT, TestGlobals.COLL)).getStatus());
    }

    @Test
    public void drop_project_of_a_registered_name_with_no_folder_unregisters_it() {
        TestUtils.deleteFolder(projectFolder(TestGlobals.PROJECT));

        assertEquals(OperationStatus.OK, dropProject(TestGlobals.PROJECT));
        assertNull(cache.getAdminProjectEntry(TestGlobals.PROJECT), "the project must no longer be registered");
        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateProjectRequest(TestGlobals.PROJECT)).getStatus());
    }

    @Test
    public void drop_collection_after_a_failed_admin_write_can_be_retried() {
        final var failuresLeft = new AtomicInteger(1);
        try (var ignored = mockStatic(AdminOperationHelper.class, invocation -> {
            if (DELETE_COLLECTION_ENTRY.equals(invocation.getMethod().getName())
                    && failuresLeft.getAndDecrement() > 0) {
                throw new IOException("disk full");
            }
            return invocation.callRealMethod();
        })) {
            assertEquals(OperationStatus.ERROR, dropCollection(TestGlobals.PROJECT, TestGlobals.COLL));
            assertFalse(collectionFolder(TestGlobals.PROJECT, TestGlobals.COLL).exists(),
                    "the first attempt deleted the folder before failing");
            assertNotNull(cache.getAdminCollectionEntry(TestGlobals.PROJECT, TestGlobals.COLL),
                    "the failed attempt left the collection registered");

            assertEquals(OperationStatus.OK, dropCollection(TestGlobals.PROJECT, TestGlobals.COLL));
            assertNull(cache.getAdminCollectionEntry(TestGlobals.PROJECT, TestGlobals.COLL));
        }
    }

    @Test
    public void test_a_dropped_collection_leaves_no_pending_ids_for_its_next_life() {
        pending.mark(TestGlobals.PROJECT, TestGlobals.COLL, "old_life");

        assertEquals(OperationStatus.OK,
                processor.processMessage(new DropCollectionRequest(TestGlobals.PROJECT, TestGlobals.COLL)).getStatus());
        assertTrue(pending.idsFor(TestGlobals.PROJECT, TestGlobals.COLL).isEmpty());

        assertEquals(OperationStatus.OK, processor
                .processMessage(new CreateCollectionRequest(TestGlobals.PROJECT, TestGlobals.COLL)).getStatus());
        pending.markRecovered(TestGlobals.PROJECT, TestGlobals.COLL, List.of("new_life"));
        assertTrue(TestUtils.dirtyIndexCollections().contains(TestGlobals.PROJECT + "|" + TestGlobals.COLL),
                "the re-created collection's first unlogged mark must write its own dirty marker");
    }

    @Test
    public void test_a_dropped_project_leaves_no_pending_ids_for_its_next_life() {
        pending.mark(TestGlobals.PROJECT, TestGlobals.COLL, "old_life");

        assertEquals(OperationStatus.OK,
                processor.processMessage(new DropProjectRequest(TestGlobals.PROJECT)).getStatus());
        assertTrue(pending.idsFor(TestGlobals.PROJECT, TestGlobals.COLL).isEmpty());

        recreateCollection();
        pending.markRecovered(TestGlobals.PROJECT, TestGlobals.COLL, List.of("new_life"));
        assertEquals(List.of("new_life"), List.copyOf(pending.idsFor(TestGlobals.PROJECT, TestGlobals.COLL)));
        assertTrue(TestUtils.dirtyIndexCollections().contains(TestGlobals.PROJECT + "|" + TestGlobals.COLL));
    }
}
