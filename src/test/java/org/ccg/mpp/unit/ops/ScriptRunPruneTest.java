package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.cluster.ClusterCoordinator;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.fs.RecordingForcer;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.SaveOperationHelper;
import org.ccg.mpp.ops.ScriptRunHistory;
import org.ccg.mpp.ops.ScriptRunKind;
import org.ccg.mpp.ops.ScriptRunPrune;
import org.ccg.mpp.ops.ScriptRunRecord;
import org.ccg.mpp.ops.admin.PageRowWriteBack;
import org.ccg.mpp.ops.req.BulkSaveRequest;
import org.ccg.mpp.test.RequiresDirectorySync;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

public class ScriptRunPruneTest {
    private static final String RUNS = Globals.SCRIPT_RUNS_COLLECTION_NAME;
    private static final long CUTOFF = 1_000_000L;
    private static final long STALE_CHUNKS = 2L;

    private final Cache cache = IocContainer.get(Cache.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final ClusterCoordinator realCoordinator = IocContainer.get(ClusterCoordinator.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
        TestUtils.setPrivateField(Configuration.getInstance(), "scriptRunHistoryEnabled", true);
        ScriptRunHistory.write(new ScriptRunRecord("creates-the-collection", ScriptRunKind.CALL_PROCEDURE,
                TestGlobals.PROJECT, "job", "proc", null, null, "u1", "u1", CUTOFF + 10_000_000L, 1L, 1,
                ScriptRunRecord.OUTCOME_OK, null, null, null, null, null, false));
    }

    @AfterEach
    public void tearDown() throws Exception {
        PageRowWriteBack.flushAll();
        TestUtils.setPrivateStaticField(ScriptRunPrune.class, "clusterCoordinator", realCoordinator);
        TestUtils.setPrivateField(Configuration.getInstance(), "scriptRunHistoryEnabled", false);
        ScriptRunHistory.reset();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void seed(String prefix, int count, long firstStartedAt) throws Exception {
        final var documents = new ArrayList<JsonObject>();
        for (var i = 0; i < count; i++) {
            final var document = new JsonObject();
            document.addProperty(Globals.PK_FIELD, prefix + i);
            document.addProperty("startedAt", firstStartedAt + i);
            document.addProperty("filler", "x".repeat(200));
            documents.add(document);
        }
        final var request = new BulkSaveRequest(TestGlobals.PROJECT, RUNS);
        request.setObjects(documents);
        locks.lock(TestGlobals.PROJECT, RUNS);
        try {
            assertEquals(OperationStatus.OK, SaveOperationHelper.executeBulkSave(request).getStatus());
        } finally {
            locks.release(TestGlobals.PROJECT, RUNS);
        }
    }

    private Set<String> remainingIds() {
        return new HashSet<>(cache.getWholeCollection(TestGlobals.PROJECT, RUNS).keySet());
    }

    @Test
    @RequiresDirectorySync
    public void test_retention_deletes_the_stale_rows_in_one_record_per_chunk() throws Exception {
        seed("stale", 1500, 1L);
        seed("fresh", 10, CUTOFF + 1L);
        final long forces;
        final int deleted;
        try (var recorder = RecordingForcer.install(true)) {
            deleted = ScriptRunPrune.prune(TestGlobals.PROJECT, CUTOFF, 0L);
            forces = recorder.forcedNames().stream().filter((RUNS + Globals.WAL_EXTENSION)::equals).count();
        }

        assertEquals(1500, deleted);
        assertEquals(11, remainingIds().size());
        assertTrue(remainingIds().stream().noneMatch(id -> id.startsWith("stale")));
        assertTrue(
                forces <= STALE_CHUNKS
                        + STALE_CHUNKS * 2L * cache.getAdminPageEntries(TestGlobals.PROJECT, RUNS).size(),
                "two chunks of deletes, each vacuuming a page at most once, forced the log " + forces + " times");
    }

    @Test
    public void test_the_size_cap_keeps_the_newest_rows() throws Exception {
        seed("run", 40, CUTOFF + 1L);

        final var deleted = ScriptRunPrune.prune(TestGlobals.PROJECT, CUTOFF, 15L);

        assertEquals(26, deleted, "41 rows, capped at 15");
        final var remaining = remainingIds();
        assertTrue(remaining.contains("creates-the-collection"), "the newest row survives");
        for (var i = 26; i < 40; i++) {
            assertTrue(remaining.contains("run" + i), "run" + i + " is among the newest 15");
        }
        assertEquals(15, remaining.size());
    }

    @Test
    public void test_a_lost_ownership_deletes_nothing() throws Exception {
        seed("stale", 20, 1L);
        final var coordinator = mock(ClusterCoordinator.class);
        when(coordinator.reserveDelete(anyString(), anyString(), anyList()))
                .thenReturn(new ClusterCoordinator.DeleteReservation(true, null));
        TestUtils.setPrivateStaticField(ScriptRunPrune.class, "clusterCoordinator", coordinator);

        assertEquals(0, ScriptRunPrune.prune(TestGlobals.PROJECT, CUTOFF, 0L));

        assertEquals(21, remainingIds().size());
        verify(coordinator, never()).replicateDelete(anyString(), anyString(), anyList(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void test_a_clustered_prune_replicates_the_deleted_ids_as_one_list() throws Exception {
        seed("stale", 20, 1L);
        final var coordinator = mock(ClusterCoordinator.class);
        when(coordinator.reserveDelete(anyString(), anyString(), anyList()))
                .thenReturn(new ClusterCoordinator.DeleteReservation(false, 7L));
        TestUtils.setPrivateStaticField(ScriptRunPrune.class, "clusterCoordinator", coordinator);

        assertEquals(20, ScriptRunPrune.prune(TestGlobals.PROJECT, CUTOFF, 0L));

        final ArgumentCaptor<List<String>> replicated = ArgumentCaptor.forClass(List.class);
        verify(coordinator).replicateDelete(eq(TestGlobals.PROJECT), eq(RUNS), replicated.capture(), eq(7L));
        assertEquals(20, replicated.getValue().size());
    }

    @Test
    public void test_a_project_without_history_prunes_nothing() throws Exception {
        assertEquals(0, ScriptRunPrune.prune("noHistoryProject", CUTOFF, 1L));
    }
}
