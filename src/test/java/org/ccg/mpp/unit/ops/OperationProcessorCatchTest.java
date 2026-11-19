package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import java.util.List;
import org.ccg.mpp.cache.AdminCache;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.cache.UserCache;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.req.AggregateRequest;
import org.ccg.mpp.ops.req.BulkSaveRequest;
import org.ccg.mpp.ops.req.CreateCollectionRequest;
import org.ccg.mpp.ops.req.CreateIndexRequest;
import org.ccg.mpp.ops.req.CreateProjectRequest;
import org.ccg.mpp.ops.req.DeleteRequest;
import org.ccg.mpp.ops.req.DropCollectionRequest;
import org.ccg.mpp.ops.req.DropIndexRequest;
import org.ccg.mpp.ops.req.FindByIdRequest;
import org.ccg.mpp.ops.req.GetProjectStatsRequest;
import org.ccg.mpp.ops.req.ListCollectionsRequest;
import org.ccg.mpp.ops.req.ListProjectsRequest;
import org.ccg.mpp.ops.req.ListUsersRequest;
import org.ccg.mpp.ops.req.OperationRequest;
import org.ccg.mpp.ops.req.ReindexRequest;
import org.ccg.mpp.ops.req.SaveRequest;
import org.ccg.mpp.ops.req.SetProjectOwnersRequest;
import org.ccg.mpp.ops.resp.OperationResponse;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class OperationProcessorCatchTest {
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private AdminCache realAdminCache;
    private UserCache realUserCache;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
        realAdminCache = TestUtils.getPrivateField(cache, "adminCache", AdminCache.class);
        realUserCache = TestUtils.getPrivateField(cache, "userCache", UserCache.class);
        final var throwing = mock(AdminCache.class, _ -> {
            throw new RuntimeException("boom");
        });
        final var throwingUser = mock(UserCache.class, _ -> {
            throw new RuntimeException("boom");
        });
        TestUtils.setPrivateField(cache, "adminCache", throwing);
        TestUtils.setPrivateField(cache, "userCache", throwingUser);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(cache, "adminCache", realAdminCache);
        TestUtils.setPrivateField(cache, "userCache", realUserCache);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static JsonObject doc() {
        final var obj = new JsonObject();
        obj.addProperty("_id", "x");
        obj.addProperty("v", 1);
        return obj;
    }

    private void assertErrors(OperationRequest request) {
        final OperationResponse response = processor.processMessage(request);
        assertNotNull(response);
        assertEquals(OperationStatus.ERROR, response.getStatus());
    }

    @Test
    public void test_data_operation_catch_blocks() {
        final var save = new SaveRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        save.setObject(doc());
        assertErrors(save);

        final var bulk = new BulkSaveRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        bulk.setObjects(List.of(doc()));
        assertErrors(bulk);

        final var find = new FindByIdRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        find.set_id("x");
        assertErrors(find);

        final var aggregate = new AggregateRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        aggregate.setAggregationSteps(List.of());
        assertErrors(aggregate);

        final var delete = new DeleteRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        delete.set_id("x");
        assertErrors(delete);
    }

    @Test
    public void test_admin_operation_catch_blocks() {
        assertErrors(new CreateProjectRequest("anotherProject"));
        assertErrors(new ListProjectsRequest());
        assertErrors(new CreateCollectionRequest(TestGlobals.PROJECT, "newColl"));
        assertErrors(new DropCollectionRequest(TestGlobals.PROJECT, TestGlobals.COLL));
        assertErrors(new ListCollectionsRequest(TestGlobals.PROJECT));
        assertErrors(new ListUsersRequest());
        assertErrors(new GetProjectStatsRequest());
        final var owners = new SetProjectOwnersRequest(TestGlobals.PROJECT);
        owners.setOwners(List.of("bob"));
        assertErrors(owners);
    }

    @Test
    public void test_index_operation_catch_blocks() {
        assertErrors(new CreateIndexRequest(TestGlobals.PROJECT, TestGlobals.COLL, "v"));
        assertErrors(new DropIndexRequest(TestGlobals.PROJECT, TestGlobals.COLL, "v"));
        assertErrors(new ReindexRequest(TestGlobals.PROJECT, TestGlobals.COLL, List.of("v")));
    }
}
