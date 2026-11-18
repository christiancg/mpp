package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.ccg.mpp.bckg_ops.TriggerExecutor;
import org.ccg.mpp.bckg_ops.events.EventType;
import org.ccg.mpp.bckg_ops.events.TriggerEvent;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.ConfigKey;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.DbEntry;
import org.ccg.mpp.data.TriggerDefinition;
import org.ccg.mpp.data.admin.AdminCollEntry;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.log.LogRateLimiter;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.CompiledProcedureCache;
import org.ccg.mpp.ops.ProcedureOperationHelper;
import org.ccg.mpp.ops.SaveOperationHelper;
import org.ccg.mpp.ops.ScriptAdmission;
import org.ccg.mpp.ops.TriggerDispatcher;
import org.ccg.mpp.ops.req.BulkSaveRequest;
import org.ccg.mpp.ops.req.SaveProcedureRequest;
import org.ccg.mpp.test.Await;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.ccg.mpp.test.TriggerTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TriggerAdmissionWaitTest {
    private static final String OWNER = "waitowner";
    private static final String OUTPUT_COLL = "waitOutput";
    private static final long DEADLINE_MS = 30_000L;
    private static final int BURST = 64;
    private static final Configuration configuration = Configuration.getInstance();
    private static final ScriptAdmission admission = IocContainer.get(ScriptAdmission.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final TriggerExecutor triggerExecutor = IocContainer.get(TriggerExecutor.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        IocContainer.get(FileSystem.class).createCollectionFile(TestGlobals.DB, OUTPUT_COLL);
        AdminOperationHelper.createPageCollections(TestGlobals.DB, OUTPUT_COLL);
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.DB, OUTPUT_COLL));
        TriggerTestSupport.createDefiner(OWNER);
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "triggersEnabled", false);
        TestUtils.setPrivateField(configuration, "triggerThreads", 2);
        TestUtils.setPrivateField(configuration, "triggerRetryBackoffMs",
                ConfigKey.TRIGGER_RETRY_BACKOFF_MS.defaultValue());
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void reset() throws Exception {
        triggerExecutor.stop();
        TestUtils.setPrivateField(configuration, "triggersEnabled", true);
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        TestUtils.setPrivateField(configuration, "triggerTimeoutMs", 5_000L);
        TestUtils.setPrivateField(configuration, "triggerMaxDepth", 3);
        TestUtils.setPrivateField(configuration, "triggerRetryBackoffMs", 20L);
        cache.removeTriggers(TestGlobals.DB, TestGlobals.COLL);
        for (final var name : IocContainer.get(FileSystem.class).metadataFiles().listProcedureNames(TestGlobals.DB)) {
            IocContainer.get(FileSystem.class).metadataFiles().deleteProcedure(TestGlobals.DB, name);
        }
        cache.removeProceduresForDatabase(TestGlobals.DB);
        IocContainer.get(CompiledProcedureCache.class).invalidateDatabase(TestGlobals.DB);
        ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.DB, "job",
                "import db from 'db'; import args from 'args'; db.save(db.name, '" + OUTPUT_COLL
                        + "', { _id: args.id, ok: true }); return 'ok';"),
                OWNER);
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL,
                List.of(new TriggerDefinition("audit", new LinkedHashSet<>(Set.of(EventType.CREATED)), "job",
                        TriggerDefinition.MODE_DOCUMENT, false, true, OWNER, 1L, 1L, 1L, OWNER)));
    }

    @AfterEach
    void restore() {
        triggerExecutor.stop();
        admission.reconfigure(0, 0L);
    }

    private static TriggerEvent event(String id) {
        final var data = new JsonObject();
        data.addProperty(Globals.PK_FIELD, id);
        return new TriggerEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, "audit", "job", false,
                List.of(DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data)), OWNER, 0);
    }

    private boolean written(String id) throws Exception {
        return !cache.getEntriesByIds(TestGlobals.DB, OUTPUT_COLL, Set.of(id)).isEmpty();
    }

    @Test
    public void test_a_trigger_refused_a_slice_waits_and_runs_once_a_permit_closes() throws Exception {
        admission.reconfigure(0, DEADLINE_MS, 1, 0);
        final var retriedBefore = triggerExecutor.getRetried();
        final var held = assertInstanceOf(ScriptAdmission.Permit.class, admission.acquireSlices(OWNER, TestGlobals.DB));
        final var dispatcher = Thread.ofPlatform().start(() -> TriggerDispatcher.dispatch(event("waited-for")));
        assertTrue(Await.parked(dispatcher, Thread.State.TIMED_WAITING, DEADLINE_MS),
                "the trigger never waited for the slice");

        held.close();
        dispatcher.join(DEADLINE_MS);

        assertTrue(written("waited-for"), "the trigger ran once the slice came back");
        assertEquals(0L, admission.getRejectedPerUser());
        assertEquals(retriedBefore, triggerExecutor.getRetried());
    }

    @Test
    public void test_a_wait_that_times_out_defers_at_the_same_attempt() throws Exception {
        admission.reconfigure(0, 50L, 1, 0);
        final var retried = new LinkedBlockingQueue<TriggerEvent>();
        triggerExecutor.start(retried::add);
        final var original = event("timed-out");
        try (var ignored = assertInstanceOf(ScriptAdmission.Permit.class,
                admission.acquireSlices(OWNER, TestGlobals.DB))) {
            final var start = System.nanoTime();
            TriggerDispatcher.dispatch(original);
            assertTrue((System.nanoTime() - start) / 1_000_000L >= 40L, "the dispatcher did not wait for a slice");
        }

        final var retry = retried.poll(DEADLINE_MS, TimeUnit.MILLISECONDS);
        assertNotNull(retry, "a timed-out wait must defer the run, not drop it");
        assertEquals(original.getAttempt(), retry.getAttempt(), "deferring for admission consumes no attempt");
        assertEquals(1L, admission.getRejectedPerUser());
        assertFalse(written("timed-out"));
    }

    @Test
    public void test_admission_deferrals_are_logged_through_a_rate_limiter() throws Exception {
        admission.reconfigure(0, 0L, 1, 0);
        try (var ignored = assertInstanceOf(ScriptAdmission.Permit.class,
                admission.acquireSlices(OWNER, TestGlobals.DB))) {
            for (var i = 0; i < 100; i++) {
                TriggerDispatcher.dispatch(event("deferred-" + i));
            }
        }

        final var limiter = TestUtils.getPrivateStaticField(TriggerDispatcher.class, "DEFERRAL_LOG",
                LogRateLimiter.class);
        assertNull(limiter.admit("probe"), "a hundred deferrals in a burst must have exhausted the window");
    }

    @Test
    public void test_eight_trigger_threads_share_four_user_slices_without_deferring() throws Exception {
        TestUtils.setPrivateField(configuration, "triggerThreads", 8);
        admission.reconfigure(0, DEADLINE_MS, 4, 0);
        final var retriedBefore = triggerExecutor.getRetried();
        triggerExecutor.start(TriggerDispatcher::dispatch);
        final var stored = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        stored.setObjects(burstIds().stream().map(id -> {
            final var object = new JsonObject();
            object.addProperty(Globals.PK_FIELD, id);
            return object;
        }).toList());
        SaveOperationHelper.executeBulkSave(stored);
        for (var i = 0; i < BURST; i++) {
            triggerExecutor.submit(event("burst-" + i));
        }

        final var ids = burstIds();
        Await.until(() -> landed(ids) == BURST, DEADLINE_MS);

        assertEquals(BURST, landed(ids), "every run must land");
        assertEquals(retriedBefore, triggerExecutor.getRetried(),
                "no run may be pushed into the one-second retry backoff");
        assertEquals(0L, admission.getRejectedPerUser());
        Await.until(() -> admission.tenantPools() == 0, DEADLINE_MS);
        assertEquals(0, admission.tenantPools(), "the definer's pools are pruned once the burst is over");
    }

    private int landed(Set<String> ids) {
        try {
            return cache.getEntriesByIds(TestGlobals.DB, OUTPUT_COLL, ids).size();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Set<String> burstIds() {
        final var ids = new LinkedHashSet<String>();
        for (var i = 0; i < BURST; i++) {
            ids.add("burst-" + i);
        }
        return ids;
    }
}
