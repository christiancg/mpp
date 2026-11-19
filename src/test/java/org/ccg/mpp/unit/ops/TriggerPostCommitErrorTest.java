package org.ccg.mpp.unit.ops;

import static org.ccg.mpp.test.TriggerTestSupport.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.ccg.mpp.bckg_ops.TriggerExecutor;
import org.ccg.mpp.bckg_ops.events.EventType;
import org.ccg.mpp.bckg_ops.events.TriggerEvent;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.data.TriggerDefinition;
import org.ccg.mpp.data.admin.AdminTriggerRunEntry;
import org.ccg.mpp.data.admin.TriggerRunStatus;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.ProcedureOperationHelper;
import org.ccg.mpp.ops.SaveOperationHelper;
import org.ccg.mpp.ops.TriggerDispatcher;
import org.ccg.mpp.ops.TriggerRunLog;
import org.ccg.mpp.ops.TxCommitLog;
import org.ccg.mpp.ops.req.FindByIdRequest;
import org.ccg.mpp.ops.req.SaveProcedureRequest;
import org.ccg.mpp.ops.req.SaveRequest;
import org.ccg.mpp.ops.resp.FindByIdResponse;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.ccg.mpp.test.TriggerTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TriggerPostCommitErrorTest {
    private static final String DEFINER = "postcommitowner";
    private static final String AUDIT_COLL = "postCommitAudit";
    private static final String FENCED_COLL = "postCommitFenced";
    private static final Configuration configuration = Configuration.getInstance();

    private final Cache cache = IocContainer.get(Cache.class);
    private final TriggerExecutor triggerExecutor = IocContainer.get(TriggerExecutor.class);
    private final CopyOnWriteArrayList<TriggerEvent> requeued = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
        IocContainer.get(FileSystem.class).createCollectionFile(TestGlobals.PROJECT, AUDIT_COLL);
        AdminOperationHelper.createPageCollections(TestGlobals.PROJECT, AUDIT_COLL);
        AdminOperationHelper
                .saveCollectionEntry(new org.ccg.mpp.data.admin.AdminCollEntry(TestGlobals.PROJECT, AUDIT_COLL));
        TriggerTestSupport.createDefiner(DEFINER);
    }

    @AfterAll
    static void tearDown() throws Exception {
        IocContainer.get(TriggerExecutor.class).stop();
        TestUtils.setPrivateField(configuration, "triggersEnabled", false);
        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled", true);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void reset() throws Exception {
        TestUtils.setPrivateField(configuration, "triggersEnabled", true);
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled", true);
        TestUtils.setPrivateField(configuration, "triggerMaxAttempts", 3);
        TestUtils.setPrivateField(configuration, "triggerRetryBackoffMs", 0L);
        TestUtils.setPrivateField(configuration, "triggerRetryMaxBackoffMs", 0L);
        TestUtils.setPrivateField(configuration, "triggerRunRetentionMs", 86_400_000L);
        TestUtils.setPrivateField(configuration, "triggerTimeoutMs", 600_000L);
        TestUtils.setPrivateField(configuration, "scriptInstructionBudget", 500_000L);
        TestUtils.setPrivateField(configuration, "scriptMaxSourceBytes", 262_144L);
        TestUtils.setPrivateField(configuration, "scriptMaxMemoryBytes", 67_108_864L);
        TestUtils.setPrivateField(configuration, "procedureCacheSize", 128);
        TestUtils.setPrivateField(configuration, "triggerMaxDepth", 3);
        requeued.clear();
        triggerExecutor.stop();
        triggerExecutor.start(requeued::add);
        TriggerTestSupport.clearTriggersAndProcedures();
        TriggerTestSupport.consumeAllPendingRuns();
    }

    private void storeProcedureThatCommitsThenExhaustsItsBudget() throws Exception {
        storeProcedureThatWritesThenExhaustsItsBudget(AUDIT_COLL);
    }

    private void storeProcedureThatWritesThenExhaustsItsBudget(String collName) throws Exception {
        ProcedureOperationHelper.executeSave(
                new SaveProcedureRequest(TestGlobals.PROJECT, "audit",
                        "import db from 'db'; import args from 'args';" + " db.save(db.name, '" + collName
                                + "', { _id: args.id, hits: 1 });"
                                + " setInterval(() => { for (let i = 0; i < 2000; i++) { } }, 0);" + " return 'ok';"),
                DEFINER);
    }

    private void installTrigger() {
        cache.putTriggers(TestGlobals.PROJECT, TestGlobals.COLL,
                List.of(new TriggerDefinition("audit", new LinkedHashSet<>(Set.of(EventType.CREATED)), "audit",
                        TriggerDefinition.MODE_DOCUMENT, false, true, DEFINER, 1L, 1L, 1L, DEFINER)));
    }

    private static void registerFencedCollectionWithoutItsFolder() throws Exception {
        AdminOperationHelper
                .saveCollectionEntry(new org.ccg.mpp.data.admin.AdminCollEntry(TestGlobals.PROJECT, FENCED_COLL));
    }

    private static void releaseTheFence() throws Exception {
        for (final var txId : TxCommitLog.localCommitTxIds()) {
            TxCommitLog.clearLocalCommit(txId);
        }
        TestUtils.releaseAllLocks();
        AdminOperationHelper.deleteCollectionEntry(TestGlobals.PROJECT, FENCED_COLL);
    }

    private static AdminTriggerRunEntry runRecord(String runId) throws Exception {
        return TriggerRunLog.pending().stream().filter(entry -> entry.getRunId().equals(runId)).findFirst()
                .orElseThrow();
    }

    private String recordedRunFor(String id) {
        return TriggerRunLog
                .record(new TriggerRunLog.TriggerRunDescriptor(TestGlobals.PROJECT, TestGlobals.COLL, "audit", "audit",
                        EventType.CREATED, false, DEFINER, 0, System.currentTimeMillis(), List.of(entry(id))));
    }

    private JsonObject auditRow(String id) {
        final var request = new FindByIdRequest(TestGlobals.PROJECT, AUDIT_COLL);
        request.set_id(id);
        final var response = new org.ccg.mpp.ops.OperationProcessor().processMessage(request);
        return response instanceof FindByIdResponse found ? found.getObject() : null;
    }

    private static void settle() {
        try {
            Thread.sleep(300L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    public void test_an_error_raised_after_the_commit_is_not_retried() throws Exception {
        storeProcedureThatCommitsThenExhaustsItsBudget();
        installTrigger();
        final var runId = recordedRunFor("post-commit");

        TriggerDispatcher.dispatch(new TriggerEvent(EventType.CREATED, TestGlobals.PROJECT, TestGlobals.COLL, "audit",
                "audit", false, List.of(entry("post-commit")), DEFINER, 0, runId, 1));
        settle();

        assertNotNull(auditRow("post-commit"), "the body's write commits before the event loop is drained");
        assertTrue(requeued.isEmpty(),
                "the run record was consumed inside the committed transaction, so a retry would re-apply the write");
    }

    @Test
    public void test_the_committed_effects_are_applied_exactly_once() throws Exception {
        storeProcedureThatCommitsThenExhaustsItsBudget();
        installTrigger();
        final var runId = recordedRunFor("once");

        TriggerDispatcher.dispatch(new TriggerEvent(EventType.CREATED, TestGlobals.PROJECT, TestGlobals.COLL, "audit",
                "audit", false, List.of(entry("once")), DEFINER, 0, runId, 1));
        settle();

        final var row = auditRow("once");
        assertNotNull(row);
        assertEquals(1L, row.get("hits").asJsonNumber().getValue().longValue());
        assertEquals(0, requeued.size());
    }

    @Test
    public void test_a_failure_before_the_commit_is_still_retried() throws Exception {
        ProcedureOperationHelper.executeSave(
                new SaveProcedureRequest(TestGlobals.PROJECT, "audit", "throw new Error('nothing committed');"),
                DEFINER);
        installTrigger();
        final var saveRequest = new SaveRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        saveRequest.setObject(entry("uncommitted").getData());
        saveRequest.set_id("uncommitted");
        SaveOperationHelper.executeSave(saveRequest);
        final var runId = recordedRunFor("uncommitted");

        TriggerDispatcher.dispatch(new TriggerEvent(EventType.CREATED, TestGlobals.PROJECT, TestGlobals.COLL, "audit",
                "audit", false, List.of(entry("uncommitted")), DEFINER, 0, runId, 1));
        settle();

        assertEquals(1, requeued.size(), "an error with no durable effects keeps its retry budget");
        assertEquals(2, requeued.getFirst().getAttempt());
    }

    @Test
    public void test_a_fenced_commit_followed_by_an_async_error_is_dead_lettered() throws Exception {
        registerFencedCollectionWithoutItsFolder();
        storeProcedureThatWritesThenExhaustsItsBudget(FENCED_COLL);
        installTrigger();
        final var saveRequest = new SaveRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        saveRequest.setObject(entry("fenced").getData());
        saveRequest.set_id("fenced");
        SaveOperationHelper.executeSave(saveRequest);
        final var runId = recordedRunFor("fenced");
        try {
            TriggerDispatcher.dispatch(new TriggerEvent(EventType.CREATED, TestGlobals.PROJECT, TestGlobals.COLL,
                    "audit", "audit", false, List.of(entry("fenced")), DEFINER, 0, runId, 1));
            settle();

            assertTrue(requeued.isEmpty(),
                    "recovery finishes a half-applied commit, so re-running the body would apply it twice");
            final var record = runRecord(runId);
            assertEquals(TriggerRunStatus.DEAD, record.getStatus());
            assertTrue(record.getLastError().startsWith("CommitFenced"), record.getLastError());
        } finally {
            releaseTheFence();
        }
    }
}
