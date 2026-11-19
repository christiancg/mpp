package org.ccg.mpp.unit.cluster;

import static org.ccg.mpp.test.ClusterTestHarness.SECRET;
import static org.ccg.mpp.test.ClusterTestHarness.node;
import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.List;
import org.ccg.mpp.cluster.ClusterRouter;
import org.ccg.mpp.cluster.PeerConnectionPool;
import org.ccg.mpp.cluster.msg.ClusterMessage;
import org.ccg.mpp.cluster.msg.ClusterMessageType;
import org.ccg.mpp.cluster.msg.ReplicationOp;
import org.ccg.mpp.cluster.msg.ReplicationPayload;
import org.ccg.mpp.cluster.ownership.OwnershipManager;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.data.admin.AdminCollEntry;
import org.ccg.mpp.ejson.EJson;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.ReplicatedApplyHelper;
import org.ccg.mpp.ops.req.AggregateRequest;
import org.ccg.mpp.ops.req.FindByIdRequest;
import org.ccg.mpp.ops.req.SaveRequest;
import org.ccg.mpp.ops.req.agg.operators.ScriptOperator;
import org.ccg.mpp.ops.req.agg.step.FilterAggregationStep;
import org.ccg.mpp.test.ClusterTestHarness;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ClusterRouterIntegrationTest {
    private final ClusterTestHarness cluster = new ClusterTestHarness();
    private final ClusterRouter router = IocContainer.get(ClusterRouter.class);
    private final OwnershipManager ownership = IocContainer.get(OwnershipManager.class);
    private final PeerConnectionPool pool = IocContainer.get(PeerConnectionPool.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final EJson eJson = IocContainer.get(EJson.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);

    private static JsonObject doc(String id) {
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        return object;
    }

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "scriptsEnabled", true);
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
        cluster.start();
    }

    @AfterEach
    public void tearDown() throws Exception {
        cluster.stop();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private String collectionOwnedByOther() {
        for (var i = 0; i < 500; i++) {
            final var coll = "routed-" + i;
            if (!ownership.isOwner(TestGlobals.PROJECT, coll)) {
                return coll;
            }
        }
        throw new IllegalStateException("no collection owned by the other node");
    }

    private void createCollection(String coll) throws Exception {
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.PROJECT, coll));
        fs.createCollectionFile(TestGlobals.PROJECT, coll);
    }

    private String rawFind(String coll, String id) {
        final var request = new FindByIdRequest(TestGlobals.PROJECT, coll);
        request.set_id(id);
        return eJson.toJson(request);
    }

    private String rawSave(String coll, String id) {
        final var request = new SaveRequest(TestGlobals.PROJECT, coll);
        request.setObject(doc(id));
        request.set_id(id);
        return eJson.toJson(request);
    }

    @Test
    public void test_forwarded_aggregate_runs_the_script_on_the_owner() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "replicationAckTimeoutMs", 30_000L);
        cluster.configureMembership(2, node("self", 19990), node("other", cluster.serverPort()));
        final var coll = collectionOwnedByOther();
        createCollection(coll);
        ReplicatedApplyHelper.apply(
                new ReplicationPayload(TestGlobals.PROJECT, coll, ReplicationOp.UPSERT, List.of(priced("s1", 5)), null),
                TestGlobals.APPLY_BUDGET_MS);
        ReplicatedApplyHelper.apply(new ReplicationPayload(TestGlobals.PROJECT, coll, ReplicationOp.UPSERT,
                List.of(priced("s2", 50)), null), TestGlobals.APPLY_BUDGET_MS);

        final var request = new AggregateRequest(TestGlobals.PROJECT, coll);
        request.setAggregationSteps(
                List.of(new FilterAggregationStep(new ScriptOperator("export default (doc) => doc.price > 10;"))));
        final var raw = "{\"type\":\"AGGREGATE\",\"projectName\":\"" + TestGlobals.PROJECT + "\",\"collectionName\":\""
                + coll + "\",\"aggregationSteps\":[{\"type\":\"FILTER\",\"operator\":"
                + "{\"script\":\"export default (doc) => doc.price > 10;\"}}]}";
        final var relayed = router.forward(request, raw, false, ClusterTestHarness.ACTING_ADMIN, null);

        assertNotNull(relayed);
        assertTrue(relayed.contains("s2"), "expected the owner's scripted result, got: " + relayed);
        assertFalse(relayed.contains("\"s1\""), "the script predicate must have excluded s1, got: " + relayed);
    }

    private static JsonObject priced(String id, double price) {
        final var object = doc(id);
        object.add("price", new org.ccg.mpp.ejson.elements.JsonNumber(price));
        return object;
    }

    @Test
    public void test_router_forwards_read_to_owner() throws Exception {
        cluster.configureMembership(2, node("self", 19990), node("other", cluster.serverPort()));
        final var coll = collectionOwnedByOther();
        createCollection(coll);
        ReplicatedApplyHelper.apply(
                new ReplicationPayload(TestGlobals.PROJECT, coll, ReplicationOp.UPSERT, List.of(doc("f1")), null),
                TestGlobals.APPLY_BUDGET_MS);

        final var request = new FindByIdRequest(TestGlobals.PROJECT, coll);
        request.set_id("f1");
        final var relayed = router.forward(request, rawFind(coll, "f1"), false, ClusterTestHarness.ACTING_ADMIN, null);

        assertNotNull(relayed);
        assertTrue(relayed.contains("f1"), "expected forwarded read response to contain the document, got: " + relayed);
    }

    @Test
    public void test_router_executes_locally_when_self_owns() throws Exception {
        cluster.configureMembership(1, node("self", cluster.serverPort()));
        final var request = new SaveRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        request.setObject(doc("local"));
        assertNull(router.forward(request, rawSave(TestGlobals.COLL, "local"), false, ClusterTestHarness.ACTING_ADMIN,
                null));
    }

    @Test
    public void test_read_falls_back_to_local_when_owner_unreachable() throws Exception {
        cluster.configureMembership(2, node("self", 19990), node("other", 1));
        final var coll = collectionOwnedByOther();
        final var request = new FindByIdRequest(TestGlobals.PROJECT, coll);
        request.set_id("x");
        assertNull(router.forward(request, rawFind(coll, "x"), false, ClusterTestHarness.ACTING_ADMIN, null));
    }

    @Test
    public void test_a_read_the_owner_never_answers_is_retryable_not_served_locally() throws Exception {
        try (var silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            cluster.configureMembership(2, node("self", 19990), node("other", silent.getLocalPort()));
            TestUtils.setPrivateField(Configuration.getInstance(), "replicationAckTimeoutMs", 300L);
            final var coll = collectionOwnedByOther();
            final var find = new FindByIdRequest(TestGlobals.PROJECT, coll);
            find.set_id("slow");
            final var aggregate = new AggregateRequest(TestGlobals.PROJECT, coll);
            aggregate.setAggregationSteps(List.of());
            final var rawAggregate = "{\"type\":\"AGGREGATE\",\"projectName\":\"" + TestGlobals.PROJECT
                    + "\",\"collectionName\":\"" + coll + "\",\"aggregationSteps\":[]}";

            final var findAnswer = router.forward(find, rawFind(coll, "slow"), false, ClusterTestHarness.ACTING_ADMIN,
                    null);
            final var aggregateAnswer = router.forward(aggregate, rawAggregate, false, ClusterTestHarness.ACTING_ADMIN,
                    null);

            assertNotNull(findAnswer, "a slow owner is not an unreachable one, so the read must not run here");
            assertTrue(findAnswer.contains(ErrorCode.OWNER_UNREACHABLE.getCode()), findAnswer);
            assertNotNull(aggregateAnswer, "a slow owner is not an unreachable one, so the read must not run here");
            assertTrue(aggregateAnswer.contains(ErrorCode.OWNER_UNREACHABLE.getCode()), aggregateAnswer);
        }
    }

    @Test
    public void test_write_errors_when_owner_unreachable() throws Exception {
        cluster.configureMembership(2, node("self", 19990), node("other", 1));
        final var coll = collectionOwnedByOther();
        final var request = new SaveRequest(TestGlobals.PROJECT, coll);
        request.setObject(doc("y"));
        final var relayed = router.forward(request, rawSave(coll, "y"), false, ClusterTestHarness.ACTING_ADMIN, null);
        assertNotNull(relayed);
        assertTrue(relayed.contains("503-4"), "expected OWNER_UNREACHABLE, got: " + relayed);
    }

    @Test
    public void test_an_error_reply_to_a_write_is_outcome_unknown() throws Exception {
        cluster.configureMembership(2, node("self", 19990), node("other", cluster.serverPort()));
        final var coll = collectionOwnedByOther();
        final var request = new SaveRequest(TestGlobals.PROJECT, coll);
        request.setObject(doc("z"));

        final var relayed = router.forward(request, "{not json", false, ClusterTestHarness.ACTING_ADMIN, null);

        assertNotNull(relayed);
        assertTrue(relayed.contains("503-8"),
                "the owner answered after processMessage ran, so the write may have committed and replicated;"
                        + " reporting it as unreachable reads as nothing happened: " + relayed);
    }

    @Test
    public void test_forward_request_handler_executes_write_on_owner() throws Exception {
        cluster.configureMembership(1, node("self", cluster.serverPort()));
        final var message = new ClusterMessage(null, ClusterMessageType.FORWARD_REQUEST, SECRET, null, null);
        message.setActingUser(ClusterTestHarness.ACTING_ADMIN);
        message.setForwardBody(rawSave(TestGlobals.COLL, "h1"));
        final var response = pool.request(cluster.serverAddress(), message, 3000);

        assertEquals(ClusterMessageType.FORWARD_RESPONSE, response.getType());
        assertTrue(response.getForwardBody().contains("h1"));
        final var find = new FindByIdRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        find.set_id("h1");
        assertEquals(OperationStatus.OK, processor.processMessage(find).getStatus());
    }
}
