package org.ccg.mpp.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.ccg.mpp.bckg_ops.events.EventType;
import org.ccg.mpp.cluster.ClusterRouter;
import org.ccg.mpp.cluster.PeerConnectionPool;
import org.ccg.mpp.cluster.Tx2pcCoordinator;
import org.ccg.mpp.cluster.msg.ClusterMessage;
import org.ccg.mpp.cluster.msg.ClusterMessageType;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.conn.ClientTracker;
import org.ccg.mpp.data.admin.AdminTriggerRunEntry;
import org.ccg.mpp.ejson.EJson;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.TriggerRunLog;
import org.ccg.mpp.ops.resp.OperationResponse;
import org.ccg.mpp.simplejs.host.EnforcingProjectAccess;
import org.ccg.mpp.test.ClusterTestHarness;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TriggerRemoteOnlyBodyTest {
    private static final String REMOTE = "127.0.0.1:59998";
    private static final String SECOND_REMOTE = "127.0.0.1:59997";
    private static final String RUN_ID = "run-whose-body-writes-remotely";
    private final Configuration config = Configuration.getInstance();
    private final Tx2pcCoordinator coordinator = IocContainer.get(Tx2pcCoordinator.class);
    private final ClusterRouter router = IocContainer.get(ClusterRouter.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final ClusterTestHarness cluster = new ClusterTestHarness();
    private final EJson eJson = IocContainer.get(EJson.class);
    private PeerConnectionPool coordinatorPool;
    private PeerConnectionPool routerPool;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
        TestUtils.resetClients();
        coordinatorPool = TestUtils.getPrivateField(coordinator, "pool", PeerConnectionPool.class);
        routerPool = TestUtils.getPrivateField(router, "pool", PeerConnectionPool.class);
        TestUtils.setPrivateField(config, "triggerRunLogEnabled", true);
        cluster.enableAlone();
        final var acknowledgingPeer = acknowledgingPeer();
        TestUtils.setPrivateField(coordinator, "pool", acknowledgingPeer);
        TestUtils.setPrivateField(router, "pool", acknowledgingPeer);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(coordinator, "pool", coordinatorPool);
        TestUtils.setPrivateField(router, "pool", routerPool);
        TestUtils.setPrivateField(config, "triggerRunLogEnabled", true);
        cluster.disable();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private PeerConnectionPool acknowledgingPeer() throws Exception {
        final var pool = mock(PeerConnectionPool.class);
        when(pool.request(any(), any(), anyLong())).thenAnswer(invocation -> {
            final ClusterMessage message = invocation.getArgument(1);
            final var reply = new ClusterMessage();
            switch (message.getType()) {
                case PREPARE_TX -> reply.setType(ClusterMessageType.PREPARE_TX_ACK);
                case COMMIT_TX -> reply.setType(ClusterMessageType.COMMIT_TX_ACK);
                case FORWARD_TX_REQUEST -> {
                    reply.setType(ClusterMessageType.FORWARD_RESPONSE);
                    reply.setForwardBody(eJson
                            .toJson(OperationResponse.ok(OperationType.COMMIT_TRANSACTION, "Transaction committed")));
                }
                default -> reply.setType(ClusterMessageType.ABORT_TX_ACK);
            }
            return reply;
        });
        return pool;
    }

    private static void recordPendingRun() throws Exception {
        AdminOperationHelper.saveTriggerRun(new AdminTriggerRunEntry(RUN_ID, 0L, TriggerRunLog.currentNodeId(),
                TestGlobals.PROJECT, TestGlobals.COLL, "audit", "mark", EventType.CREATED, false, "admin", 0,
                System.currentTimeMillis(), List.of("watched"), List.of()));
    }

    private EnforcingProjectAccess bodyThatWroteOnlyToAnotherOwner() {
        final var project = new EnforcingProjectAccess("admin", null);
        project.beginTransaction();
        clientTracker.addTransactionParticipant(sessionClientIdOf(project), REMOTE);
        return project;
    }

    private static UUID sessionClientIdOf(EnforcingProjectAccess project) {
        try {
            return TestUtils.getPrivateField(project, "sessionClientId", UUID.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    public void a_trigger_whose_body_wrote_only_to_another_owner_consumes_its_run() throws Exception {
        recordPendingRun();
        final var project = bodyThatWroteOnlyToAnotherOwner();
        project.bufferTriggerRunConsume(RUN_ID);

        assertDoesNotThrow(project::commitTransaction);

        assertTrue(TriggerRunLog.recordIdsFor(RUN_ID).isEmpty(),
                "a run whose effects committed must not stay pending, or the next restart replays its body");
    }

    @Test
    public void a_trigger_whose_body_wrote_to_two_other_owners_consumes_its_run() throws Exception {
        recordPendingRun();
        final var project = bodyThatWroteOnlyToAnotherOwner();
        clientTracker.addTransactionParticipant(sessionClientIdOf(project), SECOND_REMOTE);
        project.bufferTriggerRunConsume(RUN_ID);

        assertDoesNotThrow(project::commitTransaction);

        assertTrue(TriggerRunLog.recordIdsFor(RUN_ID).isEmpty(),
                "a 2PC commit must prepare and apply the slice holding the consume op too");
    }

    @Test
    public void buffering_the_consume_marks_the_local_slice() {
        final var project = bodyThatWroteOnlyToAnotherOwner();
        final var sessionClientId = sessionClientIdOf(project);

        project.bufferTriggerRunConsume(RUN_ID);

        assertTrue(clientTracker.hasLocalSlice(sessionClientId),
                "the consume op lives in this node's slice, so the commit must include that slice");
        project.rollbackTransaction();
    }
}
