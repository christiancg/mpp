package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.ccg.mpp.cluster.ClusterRouter;
import org.ccg.mpp.cluster.MembershipView;
import org.ccg.mpp.cluster.NodeInfo;
import org.ccg.mpp.cluster.NodeState;
import org.ccg.mpp.cluster.PeerConnectionPool;
import org.ccg.mpp.cluster.membership.MembershipService;
import org.ccg.mpp.cluster.ownership.OwnershipManager;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.data.admin.AdminUserEntry;
import org.ccg.mpp.data.auth.PermissionLevel;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.auth.AuthorizationChecker;
import org.ccg.mpp.ops.req.CancelScriptRequest;
import org.ccg.mpp.ops.req.ListScriptsRequest;
import org.ccg.mpp.ops.req.validations.RequestValidator;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class OperationProcessorScriptControlTest {
    private final ClusterRouter router = IocContainer.get(ClusterRouter.class);
    private final OwnershipManager ownership = IocContainer.get(OwnershipManager.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final PeerConnectionPool pool = IocContainer.get(PeerConnectionPool.class);
    private final Configuration config = Configuration.getInstance();
    private boolean origEnabled;
    private boolean origRouting;

    private static AdminUserEntry admin() {
        return new AdminUserEntry("admin", "hash", true, new HashSet<>(), new HashMap<>(), new HashMap<>());
    }

    // Owning a project is the widest non-admin authority there is, and it still is not enough.
    private static AdminUserEntry projectOwner() {
        final var permissions = new HashMap<String, PermissionLevel>();
        permissions.put(TestGlobals.PROJECT, PermissionLevel.READ_WRITE);
        return new AdminUserEntry("owner", "hash", false, new HashSet<>(), permissions, new HashMap<>());
    }

    private static CancelScriptRequest cancel(String runId) {
        final var request = new CancelScriptRequest();
        request.setRunId(runId);
        return request;
    }

    @BeforeEach
    public void setUp() {
        origEnabled = config.isClusterEnabled();
        origRouting = config.isScriptRoutingEnabled();
    }

    @AfterEach
    public void tearDown() throws Exception {
        pool.closeAll();
        TestUtils.setPrivateField(config, "clusterEnabled", origEnabled);
        TestUtils.setPrivateField(config, "scriptRoutingEnabled", origRouting);
        ownership.setSelfNodeId(null);
        ownership.onMembershipChanged(new MembershipView(List.of()));
        TestUtils.setPrivateField(membershipService, "members", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(membershipService, "self", null);
    }

    @Test
    public void test_list_scripts_requires_admin() {
        final var request = new ListScriptsRequest();
        assertTrue(AuthorizationChecker.check(request, admin()).isAllowed());
        assertFalse(AuthorizationChecker.check(request, projectOwner()).isAllowed());
    }

    @Test
    public void test_cancel_script_requires_admin() {
        final var request = cancel(UUID.randomUUID().toString());
        assertTrue(AuthorizationChecker.check(request, admin()).isAllowed());
        assertFalse(AuthorizationChecker.check(request, projectOwner()).isAllowed());
    }

    @Test
    public void test_list_scripts_carries_no_payload_to_validate() {
        assertTrue(RequestValidator.validate(new ListScriptsRequest()).isValid());
    }

    @Test
    public void test_cancel_script_rejects_blank_and_non_uuid_run_id() {
        assertFalse(RequestValidator.validate(cancel(null)).isValid());
        assertFalse(RequestValidator.validate(cancel("   ")).isValid());
        assertFalse(RequestValidator.validate(cancel("not-a-uuid")).isValid());
        assertTrue(RequestValidator.validate(cancel(UUID.randomUUID().toString())).isValid());
    }

    // Both operations run on the node that accepted them and fan out themselves, so the router must
    // never claim them - the treatment LIST_TRANSACTIONS gets.
    @Test
    public void test_list_scripts_is_not_routed() throws Exception {
        routableMembership();
        assertNull(router.forward(new ListScriptsRequest(), "{}", false, "admin", null));
        assertNull(router.forward(cancel(UUID.randomUUID().toString()), "{}", false, "admin", null));
    }

    // This node owns nothing, so anything routable would be forwarded away from here.
    private void routableMembership() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        TestUtils.setPrivateField(config, "scriptRoutingEnabled", true);
        final var self = new NodeInfo("self", "127.0.0.1", 9990, NodeState.ALIVE, 1L, 1L, 9);
        final var other = new NodeInfo("other", "127.0.0.1", 1, NodeState.ALIVE, 1L, 1L, 0);
        final var members = new LinkedHashMap<String, NodeInfo>();
        members.put(self.getNodeId(), self);
        members.put(other.getNodeId(), other);
        TestUtils.setPrivateField(membershipService, "members", members);
        TestUtils.setPrivateField(membershipService, "self", self);
        ownership.setSelfNodeId("other");
        ownership.onMembershipChanged(new MembershipView(List.of(self, other)));
    }

    // The trigger-run operations fan out themselves for the same reason, and for an additional one:
    // admin/trigger_runs is not replicated, so a run's record exists on exactly one node.
    @Test
    public void test_trigger_run_operations_are_not_routed() throws Exception {
        routableMembership();
        assertNull(router.forward(new org.ccg.mpp.ops.req.ListTriggerRunsRequest(), "{}", false, "admin", null));
        final var resolve = new org.ccg.mpp.ops.req.ResolveTriggerRunRequest();
        resolve.setRunId(UUID.randomUUID().toString());
        resolve.setDecision("discard");
        assertNull(router.forward(resolve, "{}", false, "admin", null));
    }

    @Test
    public void test_error_code_is_408_2() {
        assertEquals("408-2", org.ccg.mpp.ops.ErrorCode.SCRIPT_CANCELLED.getCode());
        assertEquals(org.ccg.mpp.ops.OperationStatus.ERROR, org.ccg.mpp.ops.ErrorCode.SCRIPT_CANCELLED.getStatus());
    }
}
