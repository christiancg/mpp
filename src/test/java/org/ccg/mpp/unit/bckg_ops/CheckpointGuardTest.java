package org.ccg.mpp.unit.bckg_ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.stream.Collectors;
import org.ccg.mpp.bckg_ops.PendingIndexWrites;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.data.PkIndexEntry;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.fs.RecordingForcer;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.req.CreateIndexRequest;
import org.ccg.mpp.ops.req.SaveRequest;
import org.ccg.mpp.test.PlatformSupport;
import org.ccg.mpp.test.RequiresDirectorySync;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class CheckpointGuardTest {
    private static final String DIRTY_MARKER = TestGlobals.COLL + "-indexes.dirty";
    private static final String LOG_REWRITE = TestGlobals.COLL + ".wal.repair";
    private static final long SHIPPED_CHECKPOINT_BYTES = 8L * 1024 * 1024;
    private final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private RecordingForcer recorder;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
        processor.processMessage(new CreateIndexRequest(TestGlobals.PROJECT, TestGlobals.COLL, "name"));
        recorder = RecordingForcer.install(PlatformSupport.directorySync());
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "walCheckpointBytes", SHIPPED_CHECKPOINT_BYTES);
        recorder.close();
        pendingIndexWrites.clearCollection(TestGlobals.PROJECT, TestGlobals.COLL);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void save(String id) {
        final var object = new JsonObject();
        object.addProperty("_id", id);
        object.addProperty("name", "x");
        final var save = new SaveRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        save.setObject(object);
        assertEquals(OperationStatus.OK, processor.processMessage(save).getStatus());
    }

    private void checkpointEveryWrite() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "walCheckpointBytes", 1L);
    }

    private Set<String> idsTheReplayReports() throws Exception {
        return fs.redoWriteAheadLogs().stream()
                .filter(row -> TestGlobals.PROJECT.equals(row.getProjectName())
                        && TestGlobals.COLL.equals(row.getCollectionName()))
                .map(PkIndexEntry::getValue).collect(Collectors.toSet());
    }

    @Test
    @RequiresDirectorySync
    public void test_a_checkpoint_with_pending_ids_logs_them_instead_of_writing_a_marker() throws Exception {
        checkpointEveryWrite();

        save("pending");

        assertFalse(recorder.forcedNames().contains(DIRTY_MARKER), recorder.forcedNames().toString());
        assertTrue(recorder.forcedNames().contains(LOG_REWRITE), recorder.forcedNames().toString());
        assertEquals(Set.of("pending"), idsTheReplayReports(),
                "the checkpoint dropped the record, so its log must still name the id for startup to repair");
    }

    @Test
    @RequiresDirectorySync
    public void test_a_checkpoint_with_nothing_pending_writes_no_marker() throws Exception {
        save("indexed");
        pendingIndexWrites.clearCollection(TestGlobals.PROJECT, TestGlobals.COLL);
        recorder.clear();

        fs.checkpointWriteAheadLogs(Long.MAX_VALUE);

        assertTrue(recorder.forcedNames().contains(LOG_REWRITE), recorder.forcedNames().toString());
        assertFalse(recorder.forcedNames().contains(DIRTY_MARKER), recorder.forcedNames().toString());
        assertEquals(Set.of(), idsTheReplayReports());
    }

    @Test
    public void test_an_id_still_pending_is_carried_into_every_later_checkpoint() throws Exception {
        checkpointEveryWrite();
        save("first");

        save("second");

        assertEquals(Set.of("first", "second"), idsTheReplayReports());
    }

    @Test
    public void test_an_id_whose_index_work_finished_leaves_the_next_checkpoint() throws Exception {
        checkpointEveryWrite();
        save("drained");
        pendingIndexWrites.clear(TestGlobals.PROJECT, TestGlobals.COLL, "drained", Long.MAX_VALUE);

        save("later");

        assertEquals(Set.of("later"), idsTheReplayReports());
    }

    @Test
    @RequiresDirectorySync
    public void test_shutdown_keeps_the_log_of_a_collection_with_pending_ids() {
        save("pending");
        recorder.clear();

        fs.checkpointWriteAheadLogs(Long.MAX_VALUE);

        assertFalse(recorder.forcedNames().contains(LOG_REWRITE), recorder.forcedNames().toString());
        assertFalse(recorder.forcedNames().contains(DIRTY_MARKER), recorder.forcedNames().toString());
    }

    @Test
    public void test_a_second_instance_does_not_take_over_the_guard() throws Exception {
        assertTrue(new PendingIndexWrites().idsFor(TestGlobals.PROJECT, TestGlobals.COLL).isEmpty());
        checkpointEveryWrite();

        save("pending");

        assertEquals(Set.of("pending"), idsTheReplayReports(),
                "the checkpoint must ask the published singleton, which holds the pending id, not the latest instance");
    }
}
