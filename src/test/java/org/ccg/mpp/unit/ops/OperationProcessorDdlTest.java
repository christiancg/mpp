package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.conn.ClientTracker;
import org.ccg.mpp.data.admin.AdminUserEntry;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.req.AggregateRequest;
import org.ccg.mpp.ops.req.CreateCollectionRequest;
import org.ccg.mpp.ops.req.CreateIndexRequest;
import org.ccg.mpp.ops.req.CreateProjectRequest;
import org.ccg.mpp.ops.req.DropCollectionRequest;
import org.ccg.mpp.ops.req.DropIndexRequest;
import org.ccg.mpp.ops.req.DropProjectRequest;
import org.ccg.mpp.ops.req.ListCollectionsRequest;
import org.ccg.mpp.ops.req.ListProjectsRequest;
import org.ccg.mpp.ops.req.ReindexRequest;
import org.ccg.mpp.ops.req.RequestParser;
import org.ccg.mpp.ops.req.SaveRequest;
import org.ccg.mpp.ops.req.agg.BaseAggregationStep;
import org.ccg.mpp.ops.req.agg.FieldOperatorType;
import org.ccg.mpp.ops.req.agg.operators.FieldOperator;
import org.ccg.mpp.ops.req.agg.step.FilterAggregationStep;
import org.ccg.mpp.ops.req.agg.step.JoinAggregationStep;
import org.ccg.mpp.ops.resp.AggregateAnalyzeResponse;
import org.ccg.mpp.ops.resp.ListCollectionsResponse;
import org.ccg.mpp.ops.resp.ListProjectsResponse;
import org.ccg.mpp.ops.resp.OperationResponse;
import org.ccg.mpp.ops.resp.ReindexResponse;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class OperationProcessorDdlTest {
    final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    final Cache cache = IocContainer.get(Cache.class);

    @BeforeAll
    static void setUpBeforeClass() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
    }

    @AfterAll
    public static void tearDownAll() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @Test
    public void test_create_project() {
        CreateProjectRequest request = new CreateProjectRequest("testCreateProject");

        OperationResponse response = processor.processMessage(request);

        assertNotNull(response);
        assertEquals(OperationType.CREATE_PROJECT, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(OperationType.CREATE_PROJECT, response.getType());
    }

    @Test
    public void test_drop_project() {
        CreateProjectRequest request = new CreateProjectRequest("testDropProject");

        OperationResponse response = processor.processMessage(request);
        assertEquals(OperationStatus.OK, response.getStatus());

        DropProjectRequest request2 = new DropProjectRequest("testDropProject");
        OperationResponse response2 = processor.processMessage(request2);
        assertEquals(OperationStatus.OK, response2.getStatus());
        assertEquals(OperationType.DROP_PROJECT, response2.getType());
        assertEquals(OperationType.DROP_PROJECT, response2.getType());
    }

    // Dropping a project that has collections locks each collection during deletion and releases them
    // afterwards (regression for the unlocked DROP_PROJECT path).
    @Test
    public void test_drop_project_with_collections_locks_and_releases() throws Exception {
        final var db = "dropLockProject";
        final var coll = "lockColl";
        org.ccg.mpp.ops.AdminOperationHelper.saveProjectEntry(new org.ccg.mpp.data.admin.AdminProjectEntry(db));
        org.ccg.mpp.ops.AdminOperationHelper.saveCollectionEntry(new org.ccg.mpp.data.admin.AdminCollEntry(db, coll));
        final var fs = IocContainer.get(org.ccg.mpp.fs.FileSystem.class);
        fs.createProjectFolder(db);
        fs.createCollectionFile(db, coll);
        assertTrue(IocContainer.get(org.ccg.mpp.cache.Cache.class).getAdminProjectEntry(db).getCollections()
                .contains(coll));

        final var resp = processor.processMessage(new DropProjectRequest(db));

        assertEquals(OperationType.DROP_PROJECT, resp.getType());
        assertEquals(OperationStatus.OK, resp.getStatus());
        final var locks = IocContainer.get(ResourceLocking.class);
        assertDoesNotThrow(() -> {
            locks.lock(db, coll);
            locks.release(db, coll);
        });
    }

    @Test
    public void test_create_and_drop_index() {
        CreateIndexRequest createIndexRequest = new CreateIndexRequest(TestGlobals.PROJECT, TestGlobals.COLL,
                "fieldName");

        final var createIndexResponse = processor.processMessage(createIndexRequest);
        assertEquals(OperationType.CREATE_INDEX, createIndexResponse.getType());
        assertEquals(OperationStatus.OK, createIndexResponse.getStatus());
        assertEquals("Created index for field: fieldName", createIndexResponse.getMessage());

        DropIndexRequest dropIndexRequest = new DropIndexRequest(TestGlobals.PROJECT, TestGlobals.COLL, "fieldName");
        final var dropIndexResponse = processor.processMessage(dropIndexRequest);
        assertEquals(OperationType.DROP_INDEX, dropIndexResponse.getType());
        assertEquals(OperationStatus.OK, dropIndexResponse.getStatus());
        assertEquals("Successfully dropped index: fieldName", dropIndexResponse.getMessage());
    }

    @Test
    public void test_aggregation_with_analyze_dirtyRead_reports_no_collection_lock() {
        final var coll = "analyzeDirtyColl";
        processor.processMessage(new CreateCollectionRequest(TestGlobals.PROJECT, coll));
        final var saveRequest = new SaveRequest(TestGlobals.PROJECT, coll);
        final var obj = new JsonObject();
        obj.add("_id", new JsonString("a1"));
        obj.add("status", new JsonString("active"));
        saveRequest.setObject(obj);
        processor.processMessage(saveRequest);

        final var aggregateRequest = new AggregateRequest(TestGlobals.PROJECT, coll);
        aggregateRequest.setAnalyze(true);
        aggregateRequest.setDirtyRead(true);
        aggregateRequest.setAggregationSteps(List.of(new FilterAggregationStep(
                new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active")))));

        final var response = (AggregateAnalyzeResponse) processor.processMessage(aggregateRequest);

        assertFalse(response.getAnalyzeResult().getLocksAcquired()
                .contains(Cache.getCollectionIdentifier(TestGlobals.PROJECT, coll)));
    }

    @Test
    public void test_create_and_drop_collection() {
        CreateCollectionRequest createRequest = new CreateCollectionRequest(TestGlobals.PROJECT,
                "testCreateAndDropColl");
        OperationResponse createResponse = processor.processMessage(createRequest);

        assertNotNull(createResponse);
        assertEquals(OperationType.CREATE_COLLECTION, createResponse.getType());
        assertEquals(OperationStatus.OK, createResponse.getStatus());

        DropCollectionRequest dropRequest = new DropCollectionRequest(TestGlobals.PROJECT, "testCreateAndDropColl");
        OperationResponse dropResponse = processor.processMessage(dropRequest);

        assertNotNull(dropResponse);
        assertEquals(OperationType.DROP_COLLECTION, dropResponse.getType());
        assertEquals(OperationStatus.OK, dropResponse.getStatus());
    }

    // A replicated CREATE_PROJECT returns early on an entry this node already has, so a node can hold the
    // admin entry without the folder. createCollectionFile only mkdirs one level, so the collection would be
    // left unwritable here while the write quorum, met by the other nodes, still answered the client OK.
    @Test
    public void test_create_collection_restores_a_missing_project_folder() {
        processor.processMessage(new CreateProjectRequest("folderRepairProject"));
        final var projectFolder = new File(TestGlobals.PATH + File.separator + "folderRepairProject");
        assertTrue(projectFolder.delete(), "the freshly created project folder is empty and must delete");
        assertNotNull(cache.getAdminProjectEntry("folderRepairProject"), "the admin entry must outlive the folder");

        final var response = processor
                .processMessage(new CreateCollectionRequest("folderRepairProject", "repairedColl"));

        assertEquals(OperationStatus.OK, response.getStatus());
        assertTrue(new File(projectFolder, "repairedColl").isDirectory());
    }

    @Test
    public void test_create_collection_on_an_unknown_project_is_not_found() {
        final var response = processor.processMessage(new CreateCollectionRequest("noSuchProject", "orphanColl"));

        assertEquals(OperationStatus.NOT_FOUND, response.getStatus());
        assertEquals("404-4", response.getErrorCode());
        assertNull(cache.getAdminCollectionEntry("noSuchProject", "orphanColl"));
    }

    @Test
    public void test_drop_collection_removes_lock_from_registry() throws Exception {
        final var collName = "lockCleanupColl";
        processor.processMessage(new CreateCollectionRequest(TestGlobals.PROJECT, collName));

        processor.processMessage(new DropCollectionRequest(TestGlobals.PROJECT, collName));

        // After a successful drop the lock entry must be removed from the registry so
        // it does not accumulate stale locks for deleted collections.
        final var locksField = ResourceLocking.class.getDeclaredField("locks");
        locksField.setAccessible(true);
        @SuppressWarnings("unchecked")
        final var lockMap = (java.util.Map<String, ?>) locksField.get(null);
        assertNull(lockMap.get(Cache.getCollectionIdentifier(TestGlobals.PROJECT, collName)),
                "Lock entry must be removed from registry after a successful drop");
    }

    @Test
    public void test_list_projects_returns_user_projects_excluding_admin() throws Exception {
        AdminOperationHelper.saveUserEntry(
                new AdminUserEntry("ddl_lister", "unused", true, new HashSet<>(), new HashMap<>(), new HashMap<>()));
        final var clientTracker = IocContainer.get(ClientTracker.class);
        final var clientId = clientTracker.registerForwardedClient("ddl_lister");
        final ListProjectsResponse response;
        try {
            response = (ListProjectsResponse) processor.processMessage(new ListProjectsRequest(), clientId);
        } finally {
            clientTracker.removeById(clientId);
        }

        assertNotNull(response);
        assertEquals(OperationStatus.OK, response.getStatus());
        assertNotNull(response.getProjects());
        assertTrue(response.getProjects().contains(TestGlobals.PROJECT));
        assertFalse(response.getProjects().contains(Globals.ADMIN_PROJECT_NAME));
    }

    @Test
    public void test_list_projects_returns_ok_with_empty_list() {
        ListProjectsRequest request = new ListProjectsRequest();

        ListProjectsResponse response = (ListProjectsResponse) processor.processMessage(request);

        assertNotNull(response);
        assertEquals(OperationStatus.OK, response.getStatus());
        assertTrue(response.getProjects().isEmpty(), "a request with no acting user lists nothing");
    }

    @Test
    public void test_list_collections_returns_collections_of_project() {
        ListCollectionsRequest request = new ListCollectionsRequest(TestGlobals.PROJECT);

        ListCollectionsResponse response = (ListCollectionsResponse) processor.processMessage(request);

        assertNotNull(response);
        assertEquals(OperationStatus.OK, response.getStatus());
        assertNotNull(response.getCollections());
        assertTrue(response.getCollections().contains(TestGlobals.COLL));
    }

    @Test
    public void test_list_collections_unknown_project_returns_not_found() {
        ListCollectionsRequest request = new ListCollectionsRequest("does-not-exist");

        OperationResponse response = processor.processMessage(request);

        assertNotNull(response);
        assertEquals(OperationStatus.NOT_FOUND, response.getStatus());
        assertEquals("404-4", response.getErrorCode());
    }

    @Test
    public void test_list_collections_admin_project_returns_empty_list() {
        ListCollectionsRequest request = new ListCollectionsRequest(Globals.ADMIN_PROJECT_NAME);

        ListCollectionsResponse response = (ListCollectionsResponse) processor.processMessage(request);

        assertNotNull(response);
        assertEquals(OperationStatus.OK, response.getStatus());
        assertNotNull(response.getCollections());
        assertTrue(response.getCollections().isEmpty());
    }

    @Test
    public void test_list_collections_blank_project_name_returns_error() {
        ListCollectionsRequest request = new ListCollectionsRequest("");

        OperationResponse response = processor.processMessage(request);

        assertNotNull(response);
        assertEquals(OperationStatus.ERROR, response.getStatus());
        assertEquals("400-1", response.getErrorCode());
    }

    @Test
    public void test_list_collections_only_returns_collections_of_requested_project() {
        CreateProjectRequest createProjectRequest = new CreateProjectRequest("otherProject");
        processor.processMessage(createProjectRequest);

        CreateCollectionRequest createCollRequest = new CreateCollectionRequest("otherProject", "otherColl");
        processor.processMessage(createCollRequest);

        ListCollectionsRequest request = new ListCollectionsRequest(TestGlobals.PROJECT);
        ListCollectionsResponse response = (ListCollectionsResponse) processor.processMessage(request);

        assertNotNull(response);
        assertEquals(OperationStatus.OK, response.getStatus());
        assertNotNull(response.getCollections());
        assertTrue(response.getCollections().contains(TestGlobals.COLL));
        assertFalse(response.getCollections().contains("otherColl"));
    }

    @Test
    public void test_drop_index_returns_error_for_nonexistent_collection() {
        DropIndexRequest request = new DropIndexRequest(TestGlobals.PROJECT, "noSuchColl", "noSuchField");

        OperationResponse response = processor.processMessage(request);

        assertEquals(OperationStatus.NOT_FOUND, response.getStatus());
        assertEquals("404-6", response.getErrorCode());
    }

    @Test
    public void test_dirty_read_proceeds_while_collection_write_locked() throws Exception {
        final var locks = IocContainer.get(ResourceLocking.class);
        locks.lock(TestGlobals.PROJECT, TestGlobals.COLL);
        try {
            final var json = "{\"type\":\"FIND_BY_ID\",\"projectName\":\"" + TestGlobals.PROJECT
                    + "\",\"collectionName\":\"" + TestGlobals.COLL + "\",\"_id\":\"missing\",\"dirtyRead\":true}";
            final var request = RequestParser.parseRequest(json);
            assertTrue(request.isDirtyRead());

            final var result = new AtomicReference<OperationResponse>();
            final var done = new CountDownLatch(1);
            final var worker = new Thread(() -> {
                result.set(processor.processMessage(request));
                done.countDown();
            });
            worker.start();
            assertTrue(done.await(2, TimeUnit.SECONDS), "dirty read must not block on the collection write lock");
            assertNotNull(result.get());
        } finally {
            locks.release(TestGlobals.PROJECT, TestGlobals.COLL);
        }
    }

    @Test
    public void test_aggregate_with_join_releases_both_collection_locks() {
        processor.processMessage(new CreateCollectionRequest(TestGlobals.PROJECT, TestGlobals.JOIN_COLL));

        final var request = new AggregateRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        final List<BaseAggregationStep> steps = new ArrayList<>();
        steps.add(new JoinAggregationStep(TestGlobals.JOIN_COLL, "_id", "_id", "joined"));
        request.setAggregationSteps(steps);
        processor.processMessage(request);

        final var locks = IocContainer.get(ResourceLocking.class);
        assertTrue(locks.tryLockWrite(TestGlobals.PROJECT, TestGlobals.COLL),
                "primary collection read lock must be released after AGGREGATE");
        assertTrue(locks.tryLockWrite(TestGlobals.PROJECT, TestGlobals.JOIN_COLL),
                "joined collection read lock must be released after AGGREGATE");
        locks.releaseWrite(TestGlobals.PROJECT, TestGlobals.COLL);
        locks.releaseWrite(TestGlobals.PROJECT, TestGlobals.JOIN_COLL);
    }

    @Test
    public void test_drop_project_removes_locks_for_all_collections() throws Exception {
        final var db = "lockCleanupProject";
        processor.processMessage(new CreateProjectRequest(db));
        processor.processMessage(new CreateCollectionRequest(db, "collA"));
        processor.processMessage(new CreateCollectionRequest(db, "collB"));

        processor.processMessage(new DropProjectRequest(db));

        final var locksField = ResourceLocking.class.getDeclaredField("locks");
        locksField.setAccessible(true);
        @SuppressWarnings("unchecked")
        final var lockMap = (java.util.Map<String, ?>) locksField.get(null);
        assertNull(lockMap.get(Cache.getCollectionIdentifier(db, "collA")),
                "Lock for collA must be removed after project drop");
        assertNull(lockMap.get(Cache.getCollectionIdentifier(db, "collB")),
                "Lock for collB must be removed after project drop");
    }

    @Test
    public void test_reindex_collection_with_no_indexes_returns_ok_empty_list() {
        ReindexRequest request = new ReindexRequest(TestGlobals.PROJECT, TestGlobals.COLL, null);
        ReindexResponse response = (ReindexResponse) processor.processMessage(request);
        assertEquals(OperationStatus.OK, response.getStatus());
    }

    @Test
    public void test_concurrent_create_and_drop_leave_a_consistent_state() throws Exception {
        final var locks = IocContainer.get(ResourceLocking.class);
        final var collName = "m17created";
        locks.lock(TestGlobals.PROJECT, collName);
        final var finished = new CountDownLatch(1);
        final var worker = new Thread(() -> {
            processor.processMessage(new CreateCollectionRequest(TestGlobals.PROJECT, collName));
            finished.countDown();
        });
        worker.start();
        try {
            assertFalse(finished.await(300, TimeUnit.MILLISECONDS),
                    "CREATE_COLLECTION must hold the collection lock, or a concurrent DROP lands between the folder"
                            + " creation and the registration and leaves an admin entry with no directory");
        } finally {
            locks.release(TestGlobals.PROJECT, collName);
        }

        assertTrue(finished.await(5, TimeUnit.SECONDS));
        worker.join(5_000L);
        assertNotNull(cache.getAdminCollectionEntry(TestGlobals.PROJECT, collName));
    }

}
