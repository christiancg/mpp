package org.ccg.mpp.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.ccg.mpp.bckg_ops.events.EventType;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.cluster.MembershipView;
import org.ccg.mpp.cluster.MetadataCachePruner;
import org.ccg.mpp.cluster.NodeInfo;
import org.ccg.mpp.cluster.NodeState;
import org.ccg.mpp.cluster.ownership.OwnershipManager;
import org.ccg.mpp.data.TriggerDefinition;
import org.ccg.mpp.ejson.EJson;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class MetadataCachePrunerTest {
    private final Cache cache = IocContainer.get(Cache.class);
    private final MetadataCachePruner pruner = IocContainer.get(MetadataCachePruner.class);
    private final OwnershipManager ownershipManager = IocContainer.get(OwnershipManager.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final EJson eJson = IocContainer.get(EJson.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void clear() {
        cache.removeTriggersMatching(_ -> true);
    }

    private static NodeInfo node(String id) {
        return new NodeInfo(id, "127.0.0.1", 9990, NodeState.ALIVE, 1L, System.currentTimeMillis());
    }

    private void writeTriggers() throws Exception {
        final var definition = new TriggerDefinition("t", java.util.Set.of(EventType.CREATED), "proc",
                TriggerDefinition.MODE_DOCUMENT, false, true, "alice", 1L, 1L, 1L, "alice");
        fs.createCollectionFile(TestGlobals.DB, TestGlobals.COLL);
        fs.metadataFiles().writeTriggers(TestGlobals.DB, TestGlobals.COLL,
                eJson.toJson(TriggerDefinition.toFileJson(List.of(definition))));
    }

    @Test
    public void test_keeps_triggers_for_owned_collections() throws Exception {
        writeTriggers();
        ownershipManager.setSelfNodeId("self");
        ownershipManager.onMembershipChanged(new MembershipView(List.of(node("self"))));
        assertEquals(1, cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL).size());

        pruner.onMembershipChanged(new MembershipView(List.of(node("self"))));

        assertEquals(1, cache.metadataCacheStats().triggerEntries());
    }

    @Test
    public void test_prunes_triggers_for_collections_no_longer_owned() throws Exception {
        writeTriggers();
        ownershipManager.setSelfNodeId("self");
        ownershipManager.onMembershipChanged(new MembershipView(List.of(node("self"))));
        assertEquals(1, cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL).size());

        ownershipManager.onMembershipChanged(new MembershipView(List.of(node("other"))));
        pruner.onMembershipChanged(new MembershipView(List.of(node("other"))));

        assertEquals(0, cache.metadataCacheStats().triggerEntries());
    }

    // Dropped, never blanked: TriggerDispatcher looks the list up again when it runs a queued event, so a
    // pruned collection must reload from its file rather than answer "no triggers".
    @Test
    public void test_pruned_collection_reloads_from_disk() throws Exception {
        writeTriggers();
        ownershipManager.setSelfNodeId("self");
        ownershipManager.onMembershipChanged(new MembershipView(List.of(node("self"))));
        assertEquals(1, cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL).size());

        ownershipManager.onMembershipChanged(new MembershipView(List.of(node("other"))));
        pruner.onMembershipChanged(new MembershipView(List.of(node("other"))));

        assertEquals(1, cache.getTriggersFor(TestGlobals.DB, TestGlobals.COLL).size());
    }

    @Test
    public void test_malformed_cache_key_is_ignored() {
        cache.putTriggers("nosep", "", List.of());
        ownershipManager.setSelfNodeId("self");
        ownershipManager.onMembershipChanged(new MembershipView(List.of(node("other"))));

        pruner.onMembershipChanged(new MembershipView(List.of(node("other"))));

        assertEquals(1, cache.metadataCacheStats().triggerEntries());
    }
}
