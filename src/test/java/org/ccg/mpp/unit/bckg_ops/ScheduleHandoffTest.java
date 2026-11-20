package org.ccg.mpp.unit.bckg_ops;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.ccg.mpp.bckg_ops.ScheduleExecutor;
import org.ccg.mpp.bckg_ops.ScheduleRegistry;
import org.ccg.mpp.cluster.MembershipView;
import org.ccg.mpp.cluster.NodeInfo;
import org.ccg.mpp.cluster.NodeState;
import org.ccg.mpp.cluster.ownership.OwnershipManager;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.data.ScheduleDefinition;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ScheduleHandoffTest {
    private final ScheduleRegistry registry = IocContainer.get(ScheduleRegistry.class);
    private final ScheduleExecutor executor = IocContainer.get(ScheduleExecutor.class);
    private final OwnershipManager ownership = IocContainer.get(OwnershipManager.class);
    private final org.ccg.mpp.fs.FileSystem fs = IocContainer.get(org.ccg.mpp.fs.FileSystem.class);
    private final org.ccg.mpp.cache.Cache cache = IocContainer.get(org.ccg.mpp.cache.Cache.class);
    private final org.ccg.mpp.ejson.EJson eJson = IocContainer.get(org.ccg.mpp.ejson.EJson.class);
    private boolean originalEnabled;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
        originalEnabled = Configuration.getInstance().isClusterEnabled();
        TestUtils.setPrivateField(Configuration.getInstance(), "clusterEnabled", true);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "clusterEnabled", originalEnabled);
        ownership.onMembershipChanged(new MembershipView(List.of()));
        registry.removeProject(TestGlobals.PROJECT);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static NodeInfo node() {
        final var info = new NodeInfo();
        info.setNodeId("other");
        info.setHost("127.0.0.1");
        info.setPort(9991);
        info.setState(NodeState.ALIVE);
        return info;
    }

    private ScheduleRegistry.Entry registerSchedule(String name) throws Exception {
        final var definition = new ScheduleDefinition(name, "proc", null, 1_000L, null, 0L, true, "owner", null, 1L, 1L,
                1L, "owner");
        fs.metadataFiles().writeSchedule(TestGlobals.PROJECT, name, eJson.toJson(definition.toJsonObject()));
        cache.removeSchedule(TestGlobals.PROJECT, name);
        registry.loadAll();
        return registry.entries().stream()
                .filter(entry -> entry.getName().equals(name) && entry.getProjectName().equals(TestGlobals.PROJECT))
                .findFirst().orElseThrow();
    }

    @Test
    public void test_a_non_owner_advances_nextRunAt_past_now() throws Exception {
        ownership.setSelfNodeId("not-the-owner");
        ownership.onMembershipChanged(new MembershipView(List.of(node())));

        final var entry = registerSchedule("handoff-a");
        final var now = System.currentTimeMillis() + 10_000_000L;
        executor.tick(now);

        assertTrue(entry.getNextRunAt() > now,
                "a non-owner must move past an occurrence it did not run, or handoff replays it");
    }

    @Test
    public void test_a_new_owner_does_not_replay_a_past_occurrence() throws Exception {
        ownership.setSelfNodeId("not-the-owner");
        ownership.onMembershipChanged(new MembershipView(List.of(node())));

        final var entry = registerSchedule("handoff-b");
        final var now = System.currentTimeMillis() + 10_000_000L;
        executor.tick(now);
        final var advanced = entry.getNextRunAt();

        ownership.setSelfNodeId("other");
        assertTrue(advanced > now, "the stale occurrence was consumed while this node was not the owner");
    }
}
