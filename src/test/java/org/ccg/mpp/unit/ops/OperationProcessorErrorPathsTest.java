package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.req.AggregateRequest;
import org.ccg.mpp.ops.req.BulkSaveRequest;
import org.ccg.mpp.ops.req.CreateCollectionRequest;
import org.ccg.mpp.ops.req.CreateIndexRequest;
import org.ccg.mpp.ops.req.DeleteRequest;
import org.ccg.mpp.ops.req.DropCollectionRequest;
import org.ccg.mpp.ops.req.DropIndexRequest;
import org.ccg.mpp.ops.req.DropProjectRequest;
import org.ccg.mpp.ops.req.ListCollectionsRequest;
import org.ccg.mpp.ops.req.ReindexRequest;
import org.ccg.mpp.ops.req.SaveRequest;
import org.ccg.mpp.ops.req.SetProjectOwnersRequest;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class OperationProcessorErrorPathsTest {
    private static final String MISSING_PROJECT = "missingProject";
    private static final String MISSING_COLL = "missingColl";
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void assertNotOk(org.ccg.mpp.ops.resp.OperationResponse response) {
        assertNotNull(response);
        assertNotEquals(OperationStatus.OK, response.getStatus());
    }

    private static JsonObject doc(String id) {
        final var obj = new JsonObject();
        obj.add("_id", new JsonString(id));
        return obj;
    }

    @Test
    public void test_writes_to_missing_collection_return_errors() {
        final var save = new SaveRequest(MISSING_PROJECT, MISSING_COLL);
        save.setObject(doc("x"));
        assertNotOk(processor.processMessage(save));

        final var bulk = new BulkSaveRequest(MISSING_PROJECT, MISSING_COLL);
        bulk.setObjects(List.of(doc("y")));
        assertNotOk(processor.processMessage(bulk));

        final var delete = new DeleteRequest(MISSING_PROJECT, MISSING_COLL);
        delete.set_id("nope");
        assertNotOk(processor.processMessage(delete));
    }

    @Test
    public void test_admin_ops_on_missing_targets_return_errors() {
        assertNotOk(processor.processMessage(new DropProjectRequest(MISSING_PROJECT)));
        assertNotOk(processor.processMessage(new DropCollectionRequest(MISSING_PROJECT, MISSING_COLL)));
        assertNotNull(processor.processMessage(new CreateCollectionRequest(MISSING_PROJECT, MISSING_COLL)));
        assertNotNull(processor.processMessage(new CreateIndexRequest(MISSING_PROJECT, MISSING_COLL, "f")));
        assertNotNull(processor.processMessage(new DropIndexRequest(MISSING_PROJECT, MISSING_COLL, "f")));
        assertNotNull(processor.processMessage(new ReindexRequest(MISSING_PROJECT, MISSING_COLL, List.of("f"))));
        final var owners = new SetProjectOwnersRequest(MISSING_PROJECT);
        owners.setOwners(List.of("bob"));
        assertNotNull(processor.processMessage(owners));
        assertNotNull(processor.processMessage(new ListCollectionsRequest(MISSING_PROJECT)));
    }

    @Test
    public void test_aggregate_on_missing_collection() {
        final var request = new AggregateRequest(MISSING_PROJECT, MISSING_COLL);
        request.setAggregationSteps(List.of());
        assertNotNull(processor.processMessage(request));
    }
}
