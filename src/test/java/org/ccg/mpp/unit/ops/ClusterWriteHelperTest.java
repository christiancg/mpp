package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import org.ccg.mpp.cluster.MembershipView;
import org.ccg.mpp.cluster.NodeInfo;
import org.ccg.mpp.cluster.NodeState;
import org.ccg.mpp.cluster.ownership.OwnershipManager;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.ClusterWriteHelper;
import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.resp.OperationResponse;
import org.ccg.mpp.ops.resp.SaveResponse;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ClusterWriteHelperTest {
    private final OwnershipManager ownership = IocContainer.get(OwnershipManager.class);
    private final Configuration config = Configuration.getInstance();
    private boolean origEnabled;
    private int origExpected;

    private static NodeInfo node(String id, int port) {
        return new NodeInfo(id, "127.0.0.1", port, NodeState.ALIVE, 1L, 1L);
    }

    @BeforeEach
    public void setUp() {
        origEnabled = config.isClusterEnabled();
        origExpected = config.getClusterExpectedSize();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", origEnabled);
        TestUtils.setPrivateField(config, "clusterExpectedSize", origExpected);
        ownership.setSelfNodeId(null);
        ownership.onMembershipChanged(new MembershipView(List.of()));
    }

    @Test
    public void test_guard_refuses_an_unregistered_collection_before_any_lock_when_clustering_disabled() {
        final var refusal = ClusterWriteHelper.guard(OperationType.SAVE, "neverCreatedProject", "neverCreatedColl");
        assertEquals(ErrorCode.COLLECTION_NOT_FOUND.getCode(), refusal.getErrorCode());
    }

    @Test
    public void test_after_save_passes_through_when_disabled() {
        final var response = new SaveResponse("ok", "id1");
        assertSame(response, ClusterWriteHelper.afterSave(TestGlobals.PROJECT, TestGlobals.COLL, response));
    }

    @Test
    public void test_after_save_passes_through_error_responses() {
        final var error = new OperationResponse(OperationType.SAVE, ErrorCode.ENTRY_NOT_FOUND);
        assertSame(error, ClusterWriteHelper.afterSave(TestGlobals.PROJECT, TestGlobals.COLL, error));
    }

    @Test
    public void test_after_delete_passes_through_error_responses() {
        final var error = new OperationResponse(OperationType.DELETE, ErrorCode.ENTRY_NOT_FOUND);
        assertSame(error, ClusterWriteHelper.afterDelete(TestGlobals.PROJECT, TestGlobals.COLL, "x", null, error));
    }

    @Test
    public void test_guard_maps_no_quorum() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        TestUtils.setPrivateField(config, "clusterExpectedSize", 3);
        ownership.setSelfNodeId("self");
        ownership.onMembershipChanged(new MembershipView(List.of(node("self", 9990))));
        final var response = ClusterWriteHelper.guard(OperationType.SAVE, TestGlobals.PROJECT, TestGlobals.COLL);
        assert response != null;
        assertEquals("503-2", response.getErrorCode());
    }

    @Test
    public void test_guard_maps_not_owner_with_owner_detail() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        TestUtils.setPrivateField(config, "clusterExpectedSize", 2);
        ownership.setSelfNodeId("self");
        ownership.onMembershipChanged(new MembershipView(List.of(node("self", 9990), node("other", 9991))));
        String ownedByOther = null;
        for (var i = 0; i < 500 && ownedByOther == null; i++) {
            if (!ownership.isOwner(TestGlobals.PROJECT, "coll-" + i)) {
                ownedByOther = "coll-" + i;
            }
        }
        final var response = ClusterWriteHelper.guard(OperationType.SAVE, TestGlobals.PROJECT, ownedByOther);
        assert response != null;
        assertEquals("421-1", response.getErrorCode());
    }
}
