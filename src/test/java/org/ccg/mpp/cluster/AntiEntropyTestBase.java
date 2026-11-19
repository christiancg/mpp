package org.ccg.mpp.cluster;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.cluster.membership.MembershipService;
import org.ccg.mpp.ejson.EJson;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.req.SaveRequest;
import org.ccg.mpp.test.ClusterTestHarness;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

abstract class AntiEntropyTestBase {
    protected static final String SEEDED_ID = "a";
    protected final AntiEntropyService service = IocContainer.get(AntiEntropyService.class);
    protected final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    protected final Cache cache = IocContainer.get(Cache.class);
    protected final FileSystem fs = IocContainer.get(FileSystem.class);
    protected final EJson eJson = IocContainer.get(EJson.class);
    private MembershipService realMembership;
    private PeerConnectionPool realPool;

    protected static NodeInfo node(String id, int port) {
        return ClusterTestHarness.node(id, port);
    }

    @BeforeEach
    public void setUpAntiEntropy() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
        realMembership = TestUtils.getPrivateField(service, "membershipService", MembershipService.class);
        realPool = TestUtils.getPrivateField(service, "pool", PeerConnectionPool.class);
    }

    @AfterEach
    public void tearDownAntiEntropy() throws Exception {
        TestUtils.setPrivateField(service, "membershipService", realMembership);
        TestUtils.setPrivateField(service, "pool", realPool);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    protected void seed() {
        final var obj = new JsonObject();
        obj.addProperty("_id", SEEDED_ID);
        obj.addProperty("v", 1);
        final var request = new SaveRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        request.setObject(obj);
        processor.processMessage(request);
    }

    protected boolean hasLive(String id) throws Exception {
        return cache.getPkIndexAndLoadIfNecessary(TestGlobals.PROJECT, TestGlobals.COLL).stream()
                .anyMatch(e -> e.getValue().equals(id));
    }

    protected void injectPeer(PeerConnectionPool pool) throws Exception {
        injectPeer(pool, node("peer", 5001));
    }

    protected void injectPeer(PeerConnectionPool pool, NodeInfo peer) throws Exception {
        final var self = node("self", 5000);
        final var membership = mock(MembershipService.class);
        when(membership.getSelf()).thenReturn(self);
        when(membership.membershipView()).thenReturn(new MembershipView(List.of(self, peer)));
        TestUtils.setPrivateField(service, "membershipService", membership);
        TestUtils.setPrivateField(service, "pool", pool);
    }
}
