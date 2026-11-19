package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.ccg.mpp.cluster.AdminAntiEntropyService;
import org.ccg.mpp.cluster.ClusterCoordinator;
import org.ccg.mpp.cluster.MembershipView;
import org.ccg.mpp.cluster.NodeInfo;
import org.ccg.mpp.cluster.NodeState;
import org.ccg.mpp.cluster.ReplicationOutcome;
import org.ccg.mpp.cluster.Replicator;
import org.ccg.mpp.cluster.admin.AdminRecord;
import org.ccg.mpp.cluster.msg.AdminSnapshotPayload;
import org.ccg.mpp.cluster.ownership.OwnershipManager;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.admin.AdminUserEntry;
import org.ccg.mpp.data.auth.PermissionLevel;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.ClusterAdminHelper;
import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.admin.AdminRecordKey;
import org.ccg.mpp.ops.admin.GrantPruner;
import org.ccg.mpp.ops.admin.GrantRewrite;
import org.ccg.mpp.ops.admin.RestampedRecords;
import org.ccg.mpp.ops.req.CreateCollectionRequest;
import org.ccg.mpp.ops.req.CreateUserRequest;
import org.ccg.mpp.ops.req.DeleteUserRequest;
import org.ccg.mpp.ops.req.DropCollectionRequest;
import org.ccg.mpp.ops.req.FindByIdRequest;
import org.ccg.mpp.ops.req.OperationRequest;
import org.ccg.mpp.ops.req.ReindexRequest;
import org.ccg.mpp.ops.req.SaveProcedureRequest;
import org.ccg.mpp.ops.req.SaveTriggerRequest;
import org.ccg.mpp.ops.resp.OperationResponse;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

public class ClusterAdminHelperTest {
    private final OwnershipManager ownership = IocContainer.get(OwnershipManager.class);
    private final AdminAntiEntropyService adminAntiEntropyService = IocContainer.get(AdminAntiEntropyService.class);
    private final ClusterCoordinator coordinator = IocContainer.get(ClusterCoordinator.class);
    private final Configuration config = Configuration.getInstance();
    private final Replicator replicator = mock(Replicator.class);
    private Replicator realReplicator;
    private boolean origEnabled;
    private int origExpected;

    private static NodeInfo node(String id, int port) {
        return new NodeInfo(id, "127.0.0.1", port, NodeState.ALIVE, 1L, 1L);
    }

    private static CreateCollectionRequest adminOp() {
        return new CreateCollectionRequest(TestGlobals.PROJECT, TestGlobals.COLL);
    }

    private static OperationResponse ok(OperationType type) {
        return new OperationResponse(type, OperationStatus.OK, "ok");
    }

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
        origEnabled = config.isClusterEnabled();
        origExpected = config.getClusterExpectedSize();
        realReplicator = TestUtils.getPrivateField(coordinator, "replicator", Replicator.class);
        TestUtils.setPrivateField(coordinator, "replicator", replicator);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(coordinator, "replicator", realReplicator);
        TestUtils.setPrivateField(config, "clusterEnabled", origEnabled);
        TestUtils.setPrivateField(config, "clusterExpectedSize", origExpected);
        ownership.setSelfNodeId(null);
        ownership.onMembershipChanged(new MembershipView(List.of()));
        TestUtils.setPrivateField(adminAntiEntropyService, "started", false);
        TestUtils.setPrivateField(adminAntiEntropyService, "adminSyncCompleted", new AtomicBoolean(false));
        TestUtils.standardTearDown();
    }

    private void enable(int expectedSize, boolean synced) throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        TestUtils.setPrivateField(config, "clusterExpectedSize", expectedSize);
        ownership.setSelfNodeId("self");
        ownership.onMembershipChanged(new MembershipView(List.of(node("self", 9990))));
        TestUtils.setPrivateField(adminAntiEntropyService, "started", true);
        TestUtils.setPrivateField(adminAntiEntropyService, "adminSyncCompleted", new AtomicBoolean(synced));
    }

    private void becomeNonCoordinator(int expectedSize) throws Exception {
        enable(expectedSize, true);
        ownership.setSelfNodeId("not-the-coordinator");
        ownership.onMembershipChanged(new MembershipView(List.of(node("self", 9990), node("other", 9991))));
        Assumptions.assumeFalse(ownership.isAdminCoordinator(), "this node must not be the coordinator");
    }

    private static String guardCode(OperationRequest request) {
        return Objects.requireNonNull(ClusterAdminHelper.guard(request)).getErrorCode();
    }

    @Test
    public void test_guard_ignores_non_admin_ops_and_standalone_nodes() throws Exception {
        assertNull(ClusterAdminHelper.guard(adminOp()));
        becomeNonCoordinator(2);
        assertNull(ClusterAdminHelper.guard(new FindByIdRequest(TestGlobals.PROJECT, TestGlobals.COLL)));
    }

    @Test
    public void test_guard_admits_a_synced_coordinator_with_quorum() throws Exception {
        enable(1, true);
        assertNull(ClusterAdminHelper.guard(adminOp()));
    }

    @Test
    public void test_guard_refuses_a_coordinator_that_has_not_synced() throws Exception {
        enable(1, false);
        final var realSweep = TestUtils.getPrivateField(adminAntiEntropyService, "sweep", Object.class);
        final var sweep = mock(realSweep.getClass());
        TestUtils.setPrivateField(adminAntiEntropyService, "sweep", sweep);
        try {
            assertEquals("503-5", guardCode(adminOp()));
            assertTrue(mockingDetails(sweep).getInvocations().stream()
                    .anyMatch(invocation -> invocation.getMethod().getName().equals("schedule")));
        } finally {
            TestUtils.setPrivateField(adminAntiEntropyService, "sweep", realSweep);
        }
    }

    @Test
    public void test_guard_refuses_a_non_coordinator_before_checking_quorum() throws Exception {
        becomeNonCoordinator(5);
        assertEquals("421-1", guardCode(adminOp()));
    }

    @Test
    public void test_guard_refuses_every_coordinated_op_without_quorum() throws Exception {
        enable(3, true);
        final var createUser = new CreateUserRequest();
        createUser.setUsername("bob");
        for (final var request : List.of(adminOp(), createUser,
                new SaveProcedureRequest(TestGlobals.PROJECT, "p", "return 1;"),
                new SaveTriggerRequest(TestGlobals.PROJECT, TestGlobals.COLL, "t", List.of("CREATED"), "p"))) {
            assertEquals("503-2", guardCode(request));
        }
    }

    @Test
    public void test_after_admin_op_passes_through_what_it_does_not_replicate() throws Exception {
        final var notAdmin = ok(OperationType.FIND_BY_ID);
        assertSame(notAdmin, ClusterAdminHelper.afterAdminOp(new FindByIdRequest(TestGlobals.PROJECT, TestGlobals.COLL),
                notAdmin, new RestampedRecords()));
        final var standalone = ok(OperationType.CREATE_COLLECTION);
        assertSame(standalone, ClusterAdminHelper.afterAdminOp(adminOp(), standalone, new RestampedRecords()));
        enable(1, true);
        final var failed = new OperationResponse(OperationType.CREATE_COLLECTION, ErrorCode.ENTRY_NOT_FOUND);
        assertSame(failed, ClusterAdminHelper.afterAdminOp(adminOp(), failed, new RestampedRecords()));
        verify(replicator, never()).broadcastAdmin(any());
    }

    @Test
    public void test_a_replicated_admin_op_ships_its_record_and_answers_ok() throws Exception {
        enable(1, true);
        when(replicator.broadcastAdmin(any())).thenReturn(ReplicationOutcome.QUORUM_MET);
        final var response = ok(OperationType.CREATE_COLLECTION);

        assertSame(response, ClusterAdminHelper.afterAdminOp(adminOp(), response, new RestampedRecords()));
        verify(replicator).broadcastAdmin(any());
    }

    @Test
    public void test_an_unacknowledged_admin_op_answers_outcome_unknown() throws Exception {
        enable(1, true);
        when(replicator.broadcastAdmin(any())).thenReturn(ReplicationOutcome.TIMEOUT);

        assertEquals(ErrorCode.REPLICATION_TIMEOUT.getCode(), ClusterAdminHelper
                .afterAdminOp(adminOp(), ok(OperationType.CREATE_COLLECTION), new RestampedRecords()).getErrorCode());
    }

    @Test
    public void test_an_admin_op_whose_coordinator_moved_mid_handler_answers_outcome_unknown() throws Exception {
        becomeNonCoordinator(2);

        assertEquals(ErrorCode.REPLICATION_TIMEOUT.getCode(), ClusterAdminHelper
                .afterAdminOp(adminOp(), ok(OperationType.CREATE_COLLECTION), new RestampedRecords()).getErrorCode());
    }

    @Test
    public void test_reindex_is_broadcast_instead_of_shipping_records() throws Exception {
        enable(1, true);
        when(replicator.broadcastReindex(anyString())).thenReturn(ReplicationOutcome.QUORUM_MET);
        final var response = ok(OperationType.REINDEX);

        assertSame(response, ClusterAdminHelper.afterAdminOp(
                new ReindexRequest(TestGlobals.PROJECT, TestGlobals.COLL, null), response, new RestampedRecords()));
        verify(replicator).broadcastReindex(anyString());
        verify(replicator, never()).broadcastAdmin(any());
    }

    @Test
    public void test_records_that_cannot_be_read_answer_outcome_unknown() throws Exception {
        enable(1, true);
        assertTrue(new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + Globals.CLUSTER_FOLDER + Globals.FILE_SEPARATOR
                + Globals.CLUSTER_ADMIN_TOMBSTONES_FILE).mkdirs());

        assertEquals(ErrorCode.REPLICATION_TIMEOUT.getCode(), ClusterAdminHelper
                .afterAdminOp(adminOp(), ok(OperationType.CREATE_COLLECTION), new RestampedRecords()).getErrorCode());
        verify(replicator, never()).broadcastAdmin(any());
    }

    private List<String> shippedKeys(OperationRequest request, OperationType type, RestampedRecords restamped) {
        when(replicator.broadcastAdmin(any())).thenReturn(ReplicationOutcome.QUORUM_MET);
        ClusterAdminHelper.afterAdminOp(request, ok(type), restamped);
        final var shipped = ArgumentCaptor.forClass(AdminSnapshotPayload.class);
        verify(replicator).broadcastAdmin(shipped.capture());
        return shipped.getValue().getRecords().stream().map(AdminRecord::rawKey).toList();
    }

    private static AdminUserEntry grantedOnTheTestCollection(String username) {
        return new AdminUserEntry(username, "hash", false, new HashSet<>(), Map.of(),
                Map.of(TestGlobals.PROJECT + "|" + TestGlobals.COLL, PermissionLevel.READ), Map.of());
    }

    @Test
    public void test_a_coordinator_drop_ships_exactly_the_users_its_prune_restamped() throws Exception {
        enable(1, true);
        AdminOperationHelper.saveUserEntry(grantedOnTheTestCollection("pruned_user"));
        AdminOperationHelper.saveUserEntry(
                new AdminUserEntry("untouched_user", "hash", false, new HashSet<>(), Map.of(), Map.of(), Map.of()));
        final var request = new DropCollectionRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        final var restamped = new RestampedRecords();
        GrantPruner.forDroppedCollection(TestGlobals.PROJECT, TestGlobals.COLL, new GrantRewrite.Restamp(restamped));

        final var keys = shippedKeys(request, OperationType.DROP_COLLECTION, restamped);

        assertTrue(keys.contains(AdminRecordKey.user("pruned_user").id()), keys.toString());
        assertFalse(keys.contains(AdminRecordKey.user("untouched_user").id()), keys.toString());
    }

    @Test
    public void test_a_prune_that_keeps_versions_ships_nothing_extra() throws Exception {
        enable(1, true);
        AdminOperationHelper.saveUserEntry(grantedOnTheTestCollection("kept_user"));
        final var request = new DropCollectionRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        final var restamped = new RestampedRecords();
        GrantPruner.forDroppedCollection(TestGlobals.PROJECT, TestGlobals.COLL, GrantRewrite.KEEP);

        final var keys = shippedKeys(request, OperationType.DROP_COLLECTION, restamped);

        assertEquals(List.of(AdminRecordKey.collection(TestGlobals.PROJECT, TestGlobals.COLL).id()), keys);
    }

    @Test
    public void test_a_deleted_owner_ships_the_project_its_owner_strip_restamped() throws Exception {
        enable(1, true);
        AdminOperationHelper.saveUserEntry(grantedOnTheTestCollection("owner_user"));
        assertTrue(AdminOperationHelper.updateProjectOwners(TestGlobals.PROJECT, List.of("owner_user")));
        final var request = new DeleteUserRequest();
        request.setUsername("owner_user");
        final var restamped = new RestampedRecords();
        GrantPruner.forDeletedUser("owner_user", new GrantRewrite.Restamp(restamped));

        final var keys = shippedKeys(request, OperationType.DELETE_USER, restamped);

        assertTrue(keys.contains(AdminRecordKey.project(TestGlobals.PROJECT).id()), keys.toString());
    }

    @Test
    public void aRefusedGuardLeavesNothingToReplicate() throws Exception {
        becomeNonCoordinator(2);
        assertEquals("421-1", guardCode(new DropCollectionRequest(TestGlobals.PROJECT, TestGlobals.COLL)));
        enable(1, true);
        AdminOperationHelper.saveUserEntry(grantedOnTheTestCollection("later_user"));

        final var keys = shippedKeys(adminOp(), OperationType.CREATE_COLLECTION, new RestampedRecords());

        assertEquals(List.of(AdminRecordKey.collection(TestGlobals.PROJECT, TestGlobals.COLL).id()), keys);
    }

    @Test
    public void test_ddl_definitions_and_users_are_coordinated_but_reads_and_calls_are_not() {
        for (final var type : List.of(OperationType.SAVE_PROCEDURE, OperationType.DELETE_TRIGGER,
                OperationType.SAVE_SCHEDULE, OperationType.CREATE_USER, OperationType.CHANGE_PERMISSIONS,
                OperationType.REINDEX)) {
            assertTrue(ClusterAdminHelper.isCoordinatedAdminOp(type), type.name());
        }
        for (final var type : List.of(OperationType.CALL_PROCEDURE, OperationType.LIST_PROCEDURES,
                OperationType.LIST_TRIGGERS, OperationType.RUN_SCRIPT)) {
            assertFalse(ClusterAdminHelper.isCoordinatedAdminOp(type), type.name());
        }
    }
}
