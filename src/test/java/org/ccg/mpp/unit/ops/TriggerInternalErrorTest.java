package org.ccg.mpp.unit.ops;

import static org.ccg.mpp.test.TriggerTestSupport.pendingRun;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.ccg.mpp.bckg_ops.events.EventType;
import org.ccg.mpp.bckg_ops.events.TriggerEvent;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.data.DbEntry;
import org.ccg.mpp.data.TriggerDefinition;
import org.ccg.mpp.data.admin.TriggerRunStatus;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.ProcedureOperationHelper;
import org.ccg.mpp.ops.TriggerDispatcher;
import org.ccg.mpp.ops.TriggerRunLog;
import org.ccg.mpp.ops.req.SaveProcedureRequest;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.ccg.mpp.test.TriggerTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TriggerInternalErrorTest {
    private static final String DEFINER = "arityowner";
    private static final String TRIGGER = "arityTrigger";
    private static final String FIRED_ID = "doc1";
    private static final Configuration configuration = Configuration.getInstance();
    private final Cache cache = IocContainer.get(Cache.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TriggerTestSupport.createDefiner(DEFINER);
    }

    @AfterAll
    static void tearDown() throws Exception {
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
        TestUtils.setPrivateField(configuration, "triggerMaxAttempts", 1);
        TestUtils.setPrivateField(configuration, "triggerMaxDepth", 3);
        TestUtils.setPrivateField(configuration, "triggerTimeoutMs", 5_000L);
        TestUtils.setPrivateField(configuration, "scriptTimeoutMs", 5_000L);
        TestUtils.setPrivateField(configuration, "scriptInstructionBudget", 10_000_000L);
        TriggerTestSupport.clearTriggersAndProcedures();
        TriggerTestSupport.consumeAllPendingRuns();
    }

    private void storeProcedure(String script) throws Exception {
        ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.DB, "arity", script), DEFINER);
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL,
                List.of(new TriggerDefinition(TRIGGER, new LinkedHashSet<>(Set.of(EventType.CREATED)), "arity",
                        TriggerDefinition.MODE_DOCUMENT, false, true, DEFINER, 1L, 1L, 1L, DEFINER)));
    }

    private static DbEntry firedDocument() {
        final var data = new JsonObject();
        data.add("_id", new JsonString(FIRED_ID));
        final var dbEntry = new DbEntry();
        dbEntry.setDatabaseName(TestGlobals.DB);
        dbEntry.setCollectionName(TestGlobals.COLL);
        dbEntry.set_id(FIRED_ID);
        dbEntry.setData(data);
        return dbEntry;
    }

    private static String recordRun() {
        return TriggerRunLog.record(new TriggerRunLog.TriggerRunDescriptor(TestGlobals.DB, TestGlobals.COLL, TRIGGER,
                "arity", EventType.CREATED, false, DEFINER, 0, System.currentTimeMillis(), List.of(firedDocument())));
    }

    private static TriggerEvent event(String runId) {
        return new TriggerEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, TRIGGER, "arity", false,
                List.of(firedDocument()), DEFINER, 0, runId);
    }

    @Test
    public void test_a_trigger_calling_db_save_with_too_few_arguments_is_dead_lettered() throws Exception {
        storeProcedure("import db from 'db';\ndb.save('someColl', { _id: 'x' });\n");
        final var runId = recordRun();

        TriggerDispatcher.dispatch(event(runId));

        final var record = pendingRun(runId);
        assertNotNull(record, "the run must stay visible for the operator");
        assertEquals(TriggerRunStatus.DEAD, record.getStatus());
        assertEquals(1, record.getAttempts());
        assertNotNull(record.getLastError(), "a dead-lettered run must carry why it died");
        assertTrue(record.getLastError().contains("TypeError"), record.getLastError());
    }

    @Test
    public void test_a_trigger_calling_db_aggregate_with_too_few_arguments_is_dead_lettered() throws Exception {
        storeProcedure("import db from 'db';\ndb.aggregate(db.name, 'someColl');\n");
        final var runId = recordRun();

        TriggerDispatcher.dispatch(event(runId));

        final var record = pendingRun(runId);
        assertNotNull(record);
        assertEquals(TriggerRunStatus.DEAD, record.getStatus());
        assertTrue(record.getLastError().contains("TypeError"), record.getLastError());
    }

    @Test
    public void test_a_thrown_script_error_is_still_dead_lettered_the_same_way() throws Exception {
        storeProcedure("throw new Error('nope');");
        final var runId = recordRun();

        TriggerDispatcher.dispatch(event(runId));

        final var record = pendingRun(runId);
        assertNotNull(record);
        assertEquals(TriggerRunStatus.DEAD, record.getStatus());
        assertEquals("Error: nope", record.getLastError());
    }
}
