package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.ccg.mpp.bckg_ops.events.EventType;
import org.ccg.mpp.config.ConfigKey;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.DbEntry;
import org.ccg.mpp.data.admin.AdminTriggerRunEntry;
import org.ccg.mpp.data.admin.TriggerRunStatus;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.TriggerDeadLetterCap;
import org.ccg.mpp.ops.TriggerRunLog;
import org.ccg.mpp.ops.TriggerRunRecovery;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.ccg.mpp.test.TriggerTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TriggerDeadLetterCapTest {
    private static final String OTHER_DB = "otherdb";
    private final Configuration configuration = Configuration.getInstance();

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled", true);
        TriggerTestSupport.consumeAllPendingRuns();
        configuration.override(ConfigKey.TRIGGER_DEAD_LETTER_MAX_RUNS, "3");
    }

    @AfterEach
    public void tearDown() throws Exception {
        configuration.override(ConfigKey.TRIGGER_DEAD_LETTER_MAX_RUNS,
                ConfigKey.TRIGGER_DEAD_LETTER_MAX_RUNS.defaultValue());
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static void seedDead(String dbName, String... runIds) throws Exception {
        final var now = System.currentTimeMillis();
        final var runs = new ArrayList<AdminTriggerRunEntry>();
        for (var index = 0; index < runIds.length; index++) {
            runs.add(TriggerRunIndexTest.run(runIds[index], TriggerRunLog.currentNodeId(), dbName,
                    now - 60_000L + index * 1_000L, TriggerRunStatus.DEAD));
        }
        AdminOperationHelper.saveTriggerRuns(runs);
    }

    private static String deadLetterNow() {
        final var data = new JsonObject();
        data.add(Globals.PK_FIELD, new JsonString("doc"));
        final var runId = TriggerRunLog.record(new TriggerRunLog.TriggerRunDescriptor(TestGlobals.DB, TestGlobals.COLL,
                "audit", "recalc", EventType.CREATED, false, "alice", 0, System.currentTimeMillis(),
                List.of(DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data))));
        assertNotNull(runId);
        TriggerRunLog.markAttempt(runId, TriggerRunStatus.DEAD, 3, "boom", 0L);
        return runId;
    }

    private static Set<String> deadRunsIn(String dbName) throws Exception {
        final var runs = new TreeSet<String>();
        for (final var entry : TriggerRunLog.pending()) {
            if (entry.getStatus() == TriggerRunStatus.DEAD && dbName.equals(entry.getDbName())) {
                runs.add(entry.getRunId());
            }
        }
        return runs;
    }

    @Test
    public void deadLetterCapDropsOldestBeyondCap() throws Exception {
        seedDead(TestGlobals.DB, "old-0", "old-1", "old-2", "old-3");

        final String newest;
        try (var admin = mockStatic(AdminOperationHelper.class, CALLS_REAL_METHODS)) {
            newest = deadLetterNow();

            admin.verify(() -> AdminOperationHelper.deleteTriggerRuns(List.of("old-0|0", "old-1|0")), times(1));
            admin.verify(() -> AdminOperationHelper.deleteTriggerRuns(anyList()), times(1));
        }

        assertEquals(new TreeSet<>(Set.of("old-2", "old-3", newest)), deadRunsIn(TestGlobals.DB));
    }

    @Test
    public void deadLetterCapIsPerDatabase() throws Exception {
        seedDead(OTHER_DB, "other-0", "other-1", "other-2", "other-3", "other-4");
        seedDead(TestGlobals.DB, "old-0", "old-1", "old-2");

        final var newest = deadLetterNow();

        assertEquals(new TreeSet<>(Set.of("old-1", "old-2", newest)), deadRunsIn(TestGlobals.DB));
        assertEquals(5, deadRunsIn(OTHER_DB).size(), "a dead letter in one database never trims another");
    }

    @Test
    public void sweepCapsEveryDatabase() throws Exception {
        seedDead(OTHER_DB, "other-0", "other-1", "other-2", "other-3", "other-4");
        seedDead(TestGlobals.DB, "old-0", "old-1", "old-2", "old-3");

        TriggerRunRecovery.sweep();

        assertEquals(new TreeSet<>(Set.of("other-2", "other-3", "other-4")), deadRunsIn(OTHER_DB));
        assertEquals(new TreeSet<>(Set.of("old-1", "old-2", "old-3")), deadRunsIn(TestGlobals.DB));
        assertEquals(0, TriggerDeadLetterCap.enforce(null), "a capped log has nothing left to drop");
    }

    @Test
    public void zeroCapKeepsEveryDeadRun() throws Exception {
        configuration.override(ConfigKey.TRIGGER_DEAD_LETTER_MAX_RUNS, "0");
        seedDead(TestGlobals.DB, "old-0", "old-1", "old-2", "old-3");

        deadLetterNow();
        TriggerRunRecovery.sweep();

        assertEquals(5, deadRunsIn(TestGlobals.DB).size());
    }

    @Test
    public void pendingRunsNeverCountTowardTheCap() throws Exception {
        seedDead(TestGlobals.DB, "old-0", "old-1");
        final var runs = new ArrayList<AdminTriggerRunEntry>();
        for (var index = 0; index < 5; index++) {
            runs.add(TriggerRunIndexTest.run("live-" + index, TriggerRunLog.currentNodeId(), TestGlobals.DB,
                    System.currentTimeMillis(), TriggerRunStatus.PENDING));
        }
        AdminOperationHelper.saveTriggerRuns(runs);

        deadLetterNow();

        assertEquals(3, deadRunsIn(TestGlobals.DB).size());
        assertEquals(8, TriggerRunLog.pending().size());
    }
}
