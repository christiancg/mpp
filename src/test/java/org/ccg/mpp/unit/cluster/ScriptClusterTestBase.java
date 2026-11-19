package org.ccg.mpp.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.cluster.ClusterRouter;
import org.ccg.mpp.cluster.NodeInfo;
import org.ccg.mpp.cluster.NodeState;
import org.ccg.mpp.cluster.ScriptPlacement;
import org.ccg.mpp.cluster.ownership.OwnershipManager;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.conn.ClientTracker;
import org.ccg.mpp.data.admin.AdminCollEntry;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.req.FindByIdRequest;
import org.ccg.mpp.test.ClusterTestHarness;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

abstract class ScriptClusterTestBase {
    static final String ADMIN = "scriptrouteadmin";
    private static final int SCRIPT_CAPACITY = 10;
    private static final int PEER_LOAD = 2;
    final ClusterTestHarness cluster = new ClusterTestHarness();
    final Configuration config = Configuration.getInstance();
    final OwnershipManager ownership = IocContainer.get(OwnershipManager.class);
    final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    final FileSystem fs = IocContainer.get(FileSystem.class);
    final ClusterRouter router = IocContainer.get(ClusterRouter.class);
    final ScriptPlacement scriptPlacement = IocContainer.get(ScriptPlacement.class);
    boolean origScripts;
    boolean origScriptRouting;
    int origLocalityWeight;

    static NodeInfo node(String id, int port) {
        return ClusterTestHarness.node(id, port);
    }

    static NodeInfo node(String id, int port, int scriptLoad) {
        return new NodeInfo(id, "127.0.0.1", port, NodeState.ALIVE, 1L, 1L, scriptLoad);
    }

    // The cap is arbitrary and shared by both sides of the pair; only the ratio between them decides.
    static NodeInfo cappedNode(String id, int port, int scriptLoad) {
        return new NodeInfo(id, "127.0.0.1", port, NodeState.ALIVE, 1L, 1L, scriptLoad, SCRIPT_CAPACITY);
    }

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
        TestUtils.resetClients();
        createAdminUser();
        origScripts = config.isScriptsEnabled();
        origScriptRouting = config.isScriptRoutingEnabled();
        origLocalityWeight = config.getScriptLocalityWeight();
        cluster.start(false, 1000L);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(config, "scriptsEnabled", origScripts);
        TestUtils.setPrivateField(config, "scriptRoutingEnabled", origScriptRouting);
        TestUtils.setPrivateField(config, "scriptLocalityWeight", origLocalityWeight);
        cluster.stop();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    static void createAdminUser() {
        TestUtils.createAdmin(ADMIN);
    }

    static JsonObject doc(String id) {
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        object.add("value", new JsonString("hello"));
        return object;
    }

    void configureMembership(int expectedSize, NodeInfo self, NodeInfo... others) throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        cluster.configureMembership(expectedSize, self, others);
    }

    String collectionOwnedByOther() {
        for (var i = 0; i < 500; i++) {
            final var coll = "script-routed-" + i;
            if (!ownership.isOwner(TestGlobals.PROJECT, coll)) {
                return coll;
            }
        }
        throw new IllegalStateException("no collection owned by the other node");
    }

    void createCollection(String coll) throws Exception {
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.PROJECT, coll));
        fs.createCollectionFile(TestGlobals.PROJECT, coll);
    }

    OperationStatus findStatus(String coll, String id) {
        final var request = new FindByIdRequest(TestGlobals.PROJECT, coll);
        request.set_id(id);
        return processor.processMessage(request).getStatus();
    }

    // Self carries a load the peer does not, so placement always prefers the peer. Locality is pinned off
    // so these cases keep exercising the load path in isolation.
    void enableScriptRouting() throws Exception {
        configureMembership(2, node("self", 19990, 9), node("other", cluster.serverPort(), 0));
        TestUtils.setPrivateField(config, "scriptsEnabled", true);
        TestUtils.setPrivateField(config, "scriptRoutingEnabled", true);
        TestUtils.setPrivateField(config, "scriptLocalityWeight", 0);
    }

    // The peer carries the higher load, so load alone keeps the run here - no tie, and so no dependence
    // on how an undecided pair is resolved.
    void configureMembershipWithAPeerOwningTheWholeProject() throws Exception {
        final var collections = IocContainer.get(Cache.class).getCollectionNamesForProject(TestGlobals.PROJECT);
        assertFalse(collections.isEmpty(), "the fixture project must have at least one collection");
        for (var i = 0; i < 500; i++) {
            final var peer = cappedNode("z-owner-" + i, cluster.serverPort(), PEER_LOAD);
            configureMembership(2, cappedNode("self", 19990, 0), peer);
            if (collections.stream()
                    .allMatch(coll -> peer.getNodeId().equals(ownership.ownerFor(TestGlobals.PROJECT, coll)))) {
                return;
            }
        }
        throw new IllegalStateException("no peer id owning every collection of the project");
    }
}
