package org.ccg.mpp.unit.bckg_ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.ccg.mpp.bckg_ops.ScheduleRegistry;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.ScheduleDefinition;
import org.ccg.mpp.data.admin.AdminProjectEntry;
import org.ccg.mpp.ejson.EJson;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class ScheduleRegistryTest {
    private static final String OTHER_PROJECT = "otherProject";
    private static final String DROPPED_PROJECT = "droppedProject";
    private static final long RACE_WAIT_SECONDS = 5L;
    private static final long MUTATION_HEAD_START_MILLIS = 300L;
    private static final Configuration configuration = Configuration.getInstance();
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final EJson eJson = IocContainer.get(EJson.class);
    private final ScheduleRegistry registry = IocContainer.get(ScheduleRegistry.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
        AdminOperationHelper.saveProjectEntry(new AdminProjectEntry(OTHER_PROJECT));
        IocContainer.get(FileSystem.class).createProjectFolder(OTHER_PROJECT);
    }

    @AfterAll
    static void tearDown() throws Exception {
        IocContainer.get(ScheduleRegistry.class).clear();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void reset() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptTimeZone", "UTC");
        for (final var projectName : new String[]{TestGlobals.PROJECT, OTHER_PROJECT}) {
            for (final var name : fs.metadataFiles().listScheduleNames(projectName)) {
                fs.metadataFiles().deleteSchedule(projectName, name);
            }
            cache.removeSchedulesForProject(projectName);
        }
        registry.clear();
    }

    private interface Mutation {
        void run() throws Exception;
    }

    private void writeSchedule(String projectName, String name, String cron, long intervalMs) throws Exception {
        writeSchedule(projectName, name, cron, intervalMs, true);
    }

    private void writeSchedule(String projectName, String name, String cron, long intervalMs, boolean enabled)
            throws Exception {
        final var definition = new ScheduleDefinition(name, "p", cron, intervalMs, null, 0L, enabled, "alice", null, 1L,
                1L, 1L, "alice");
        fs.metadataFiles().writeSchedule(projectName, name, eJson.toJson(definition.toJsonObject()));
        cache.removeSchedule(projectName, name);
    }

    private void writeCorruptSchedule(String name) throws Exception {
        fs.metadataFiles().writeSchedule(TestGlobals.PROJECT, name, "{\"name\": \"" + name);
        cache.removeSchedule(TestGlobals.PROJECT, name);
    }

    @Test
    public void test_a_folder_that_cannot_be_listed_keeps_the_registered_schedules() throws Exception {
        writeSchedule(OTHER_PROJECT, "kept", null, 2000L);
        registry.reload(OTHER_PROJECT);
        assertNotNull(registry.get(OTHER_PROJECT, "kept"));
        final var folder = new File(TestUtils.getDbPath(fs), OTHER_PROJECT + File.separator + Globals.SCHEDULES_FOLDER);
        TestUtils.deleteFolder(folder);
        Files.writeString(folder.toPath(), "not a folder");
        try {
            registry.reload(OTHER_PROJECT);

            assertNotNull(registry.get(OTHER_PROJECT, "kept"), "a listing that failed is not an empty folder");
        } finally {
            Files.delete(folder.toPath());
        }
    }

    @Test
    public void test_an_unreadable_definition_is_skipped_and_its_siblings_still_load() throws Exception {
        writeSchedule(TestGlobals.PROJECT, "good", null, 2000L);
        writeCorruptSchedule("torn");

        assertDoesNotThrow(registry::loadAll, "one torn file must not stop the node booting");

        assertNotNull(registry.get(TestGlobals.PROJECT, "good"));
        assertNull(registry.get(TestGlobals.PROJECT, "torn"), "an unreadable definition is not registered");
    }

    @Test
    public void test_an_unreadable_definition_does_not_unregister_a_working_entry() throws Exception {
        writeSchedule(TestGlobals.PROJECT, "flaky", null, 2000L);
        registry.loadAll();
        assertNotNull(registry.get(TestGlobals.PROJECT, "flaky"));

        writeCorruptSchedule("flaky");
        registry.reload(TestGlobals.PROJECT);

        assertNotNull(registry.get(TestGlobals.PROJECT, "flaky"),
                "a read failure is not an absence, so the entry must survive the reload's removeIf");
    }

    @Test
    public void test_an_interval_run_advances_from_the_scheduled_instant() throws Exception {
        writeSchedule(TestGlobals.PROJECT, "ticker", null, 60_000L);
        registry.loadAll();
        final var entry = registry.get(TestGlobals.PROJECT, "ticker");
        assertNotNull(entry);
        entry.setNextRunAt(1_000_000L);

        assertEquals(1_060_000L, registry.nextRunAfter(entry, 1_000_500L),
                "folding the tick's latency into every period loses fires over a day");
    }

    @Test
    public void test_an_interval_run_skips_rather_than_storms_after_downtime() throws Exception {
        writeSchedule(TestGlobals.PROJECT, "ticker", null, 60_000L);
        registry.loadAll();
        final var entry = registry.get(TestGlobals.PROJECT, "ticker");
        assertNotNull(entry);
        entry.setNextRunAt(1_000_000L);

        final var next = registry.nextRunAfter(entry, 4_600_000L);

        assertTrue(next > 4_600_000L, "the missed hour must not queue up sixty catch-up runs");
        assertEquals(4_660_000L, next);
    }

    @Test
    public void test_a_first_interval_run_is_one_period_from_now() throws Exception {
        writeSchedule(TestGlobals.PROJECT, "fresh", null, 60_000L);
        registry.loadAll();
        final var entry = registry.get(TestGlobals.PROJECT, "fresh");
        assertNotNull(entry);

        assertTrue(entry.getNextRunAt() > System.currentTimeMillis() + 50_000L);
    }

    @Test
    public void test_load_all_picks_up_every_project() throws Exception {
        writeSchedule(TestGlobals.PROJECT, "a", null, 2000L);
        writeSchedule(OTHER_PROJECT, "b", "0 3 * * *", 0L);
        registry.loadAll();
        assertEquals(2, registry.size());
        assertNotNull(registry.get(TestGlobals.PROJECT, "a"));
        assertNotNull(registry.get(OTHER_PROJECT, "b"));
    }

    @Test
    public void test_reload_replaces_one_project_only() throws Exception {
        writeSchedule(TestGlobals.PROJECT, "a", null, 2000L);
        writeSchedule(OTHER_PROJECT, "b", null, 2000L);
        registry.loadAll();

        fs.metadataFiles().deleteSchedule(TestGlobals.PROJECT, "a");
        cache.removeSchedule(TestGlobals.PROJECT, "a");
        registry.reload(TestGlobals.PROJECT);
        assertNull(registry.get(TestGlobals.PROJECT, "a"));
        assertNotNull(registry.get(OTHER_PROJECT, "b"));
    }

    @Test
    public void test_remove_project_drops_its_entries() throws Exception {
        writeSchedule(TestGlobals.PROJECT, "a", null, 2000L);
        writeSchedule(OTHER_PROJECT, "b", null, 2000L);
        registry.loadAll();
        registry.removeProject(TestGlobals.PROJECT);
        assertNull(registry.get(TestGlobals.PROJECT, "a"));
        assertNotNull(registry.get(OTHER_PROJECT, "b"));
    }

    @Test
    public void test_next_run_at_is_in_the_future_after_load() throws Exception {
        writeSchedule(TestGlobals.PROJECT, "a", null, 2000L);
        writeSchedule(TestGlobals.PROJECT, "b", "0 3 * * *", 0L);
        registry.loadAll();
        final var now = System.currentTimeMillis();
        assertTrue(registry.get(TestGlobals.PROJECT, "a").getNextRunAt() > now);
        assertTrue(registry.get(TestGlobals.PROJECT, "b").getNextRunAt() > now);
    }

    @Test
    public void test_reload_preserves_the_next_run_of_an_unchanged_schedule() throws Exception {
        writeSchedule(TestGlobals.PROJECT, "a", null, 2000L);
        registry.loadAll();
        final var first = registry.get(TestGlobals.PROJECT, "a").getNextRunAt();
        registry.reload(TestGlobals.PROJECT);
        assertEquals(first, registry.get(TestGlobals.PROJECT, "a").getNextRunAt());
    }

    @Test
    public void test_an_unsatisfiable_cron_yields_no_next_run() throws Exception {
        writeSchedule(TestGlobals.PROJECT, "a", "0 0 30 2 *", 0L);
        registry.loadAll();
        assertEquals(0L, registry.get(TestGlobals.PROJECT, "a").getNextRunAt());
    }

    @Test
    public void test_an_unparseable_cron_is_not_registered() throws Exception {
        writeSchedule(TestGlobals.PROJECT, "a", "not a cron", 0L);
        registry.loadAll();
        assertNull(registry.get(TestGlobals.PROJECT, "a"));
    }

    @Test
    public void test_entries_exposes_the_definition_and_key() throws Exception {
        writeSchedule(TestGlobals.PROJECT, "a", null, 2000L);
        registry.loadAll();
        final var entry = registry.entries().iterator().next();
        assertEquals(TestGlobals.PROJECT, entry.getProjectName());
        assertEquals("a", entry.getName());
        assertEquals(TestGlobals.PROJECT + "|a", entry.key());
        assertNull(entry.getCron());
        assertEquals("p", entry.getDefinition().getProcedureName());
    }

    private void refreshRacing(String projectName, Mutation mutation) throws Exception {
        final var definitionRead = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var firstRead = new AtomicBoolean(true);
        final var stalledCache = Mockito.spy(cache);
        Mockito.doAnswer(invocation -> {
            final var definition = invocation.callRealMethod();
            if (firstRead.compareAndSet(true, false)) {
                definitionRead.countDown();
                assertTrue(release.await(RACE_WAIT_SECONDS, TimeUnit.SECONDS));
            }
            return definition;
        }).when(stalledCache).getSchedule(Mockito.anyString(), Mockito.anyString());
        TestUtils.setPrivateField(registry, "cache", stalledCache);
        try {
            final var refresh = Thread.ofPlatform().start(() -> registry.reload(projectName));
            assertTrue(definitionRead.await(RACE_WAIT_SECONDS, TimeUnit.SECONDS));
            final var failure = new Exception[1];
            final var mutator = Thread.ofPlatform().start(() -> {
                try {
                    mutation.run();
                } catch (Exception e) {
                    failure[0] = e;
                }
            });
            mutator.join(MUTATION_HEAD_START_MILLIS);
            release.countDown();
            refresh.join(TimeUnit.SECONDS.toMillis(RACE_WAIT_SECONDS));
            mutator.join(TimeUnit.SECONDS.toMillis(RACE_WAIT_SECONDS));
            assertNull(failure[0]);
        } finally {
            TestUtils.setPrivateField(registry, "cache", cache);
        }
    }

    @Test
    public void test_a_refresh_that_read_a_definition_before_its_delete_does_not_reinstate_it() throws Exception {
        writeSchedule(TestGlobals.PROJECT, "deleted", null, 2000L);
        registry.loadAll();

        refreshRacing(TestGlobals.PROJECT, () -> {
            fs.metadataFiles().deleteSchedule(TestGlobals.PROJECT, "deleted");
            cache.removeSchedule(TestGlobals.PROJECT, "deleted");
            registry.reload(TestGlobals.PROJECT);
        });

        assertNull(registry.get(TestGlobals.PROJECT, "deleted"),
                "an acknowledged DELETE_SCHEDULE must not be undone by a refresh that read the old file");
    }

    @Test
    public void test_a_refresh_racing_a_disable_does_not_re_enable() throws Exception {
        writeSchedule(TestGlobals.PROJECT, "paused", null, 2000L);
        registry.loadAll();

        refreshRacing(TestGlobals.PROJECT, () -> {
            writeSchedule(TestGlobals.PROJECT, "paused", null, 2000L, false);
            registry.reload(TestGlobals.PROJECT);
        });

        assertFalse(registry.get(TestGlobals.PROJECT, "paused").getDefinition().isEnabled(),
                "the disabled definition saved last must be the one registered");
    }

    @Test
    public void test_a_refresh_racing_drop_project_leaves_no_entry() throws Exception {
        AdminOperationHelper.saveProjectEntry(new AdminProjectEntry(DROPPED_PROJECT));
        fs.createProjectFolder(DROPPED_PROJECT);
        writeSchedule(DROPPED_PROJECT, "ghost", null, 2000L);
        registry.loadAll();

        refreshRacing(DROPPED_PROJECT, () -> {
            fs.deleteProject(DROPPED_PROJECT);
            cache.evictProject(DROPPED_PROJECT);
            AdminOperationHelper.deleteProjectEntry(DROPPED_PROJECT);
            registry.removeProject(DROPPED_PROJECT);
        });
        registry.loadAll();

        assertNull(registry.get(DROPPED_PROJECT, "ghost"),
                "a dropped project's schedule must not outlive the drop until the next restart");
    }
}
