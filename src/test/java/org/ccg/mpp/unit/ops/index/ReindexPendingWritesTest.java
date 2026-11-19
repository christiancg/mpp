package org.ccg.mpp.unit.ops.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import org.ccg.mpp.bckg_ops.PendingIndexWrites;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.ejson.elements.JsonBaseElement;
import org.ccg.mpp.ejson.elements.JsonNumber;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.req.AggregateRequest;
import org.ccg.mpp.ops.req.CreateIndexRequest;
import org.ccg.mpp.ops.req.ReindexRequest;
import org.ccg.mpp.ops.req.SaveRequest;
import org.ccg.mpp.ops.req.agg.FieldOperatorType;
import org.ccg.mpp.ops.req.agg.operators.FieldOperator;
import org.ccg.mpp.ops.req.agg.step.FilterAggregationStep;
import org.ccg.mpp.ops.resp.AggregateResponse;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ReindexPendingWritesTest {
    private static final String FIELD = "status";
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
    }

    private void seedIndexedNameAndLabel() {
        saveIndexableDocument();
        processor.processMessage(new CreateIndexRequest(TestGlobals.PROJECT, TestGlobals.COLL, "name"));
        processor.processMessage(new CreateIndexRequest(TestGlobals.PROJECT, TestGlobals.COLL, "label"));
    }

    private void seedStatuses() {
        seed("s0", new JsonString("active"));
        seed("s1", new JsonString("active"));
        seed("n0", new JsonNumber(7));
        processor.processMessage(new CreateIndexRequest(TestGlobals.PROJECT, TestGlobals.COLL, FIELD));
    }

    @AfterEach
    public void tearDown() throws Exception {
        pendingIndexWrites.clearCollection(TestGlobals.PROJECT, TestGlobals.COLL);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void saveIndexableDocument() {
        final var request = new SaveRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        final var object = new JsonObject();
        object.add("_id", new JsonString("doc-1"));
        object.add("name", new JsonString("alpha"));
        object.add("label", new JsonString("first"));
        request.setObject(object);
        processor.processMessage(request);
    }

    private boolean markerPresent() {
        return TestUtils.dirtyIndexCollections().stream().anyMatch(entry -> entry.contains(TestGlobals.COLL));
    }

    @Test
    public void test_a_full_reindex_clears_the_overlay_and_the_marker() {
        seedIndexedNameAndLabel();
        pendingIndexWrites.markRecovered(TestGlobals.PROJECT, TestGlobals.COLL, List.of("stranded"));
        assertTrue(markerPresent());

        final var response = processor
                .processMessage(new ReindexRequest(TestGlobals.PROJECT, TestGlobals.COLL, List.of()));

        assertEquals(OperationStatus.OK, response.getStatus());
        assertTrue(pendingIndexWrites.idsFor(TestGlobals.PROJECT, TestGlobals.COLL).isEmpty(),
                "a full rebuild read the authoritative documents, so the marks it inherited are redundant");
        assertFalse(markerPresent());
    }

    @Test
    public void test_a_partial_reindex_leaves_the_overlay_and_the_marker() {
        seedIndexedNameAndLabel();
        pendingIndexWrites.markRecovered(TestGlobals.PROJECT, TestGlobals.COLL, List.of("stranded"));
        final var request = new ReindexRequest(TestGlobals.PROJECT, TestGlobals.COLL, List.of("name"));

        final var response = processor.processMessage(request);

        assertEquals(OperationStatus.OK, response.getStatus());
        assertTrue(pendingIndexWrites.idsFor(TestGlobals.PROJECT, TestGlobals.COLL).contains("stranded"),
                "the fields it skipped were never re-derived, so the marks still matter");
        assertTrue(markerPresent());
    }

    @Test
    public void test_an_event_queued_before_a_full_reindex_does_not_consume_a_later_writes_mark() {
        seedIndexedNameAndLabel();
        final var queuedGeneration = pendingIndexWrites.mark(TestGlobals.PROJECT, TestGlobals.COLL, "doc-1");
        processor.processMessage(new ReindexRequest(TestGlobals.PROJECT, TestGlobals.COLL, List.of()));
        pendingIndexWrites.mark(TestGlobals.PROJECT, TestGlobals.COLL, "doc-1");

        pendingIndexWrites.clear(TestGlobals.PROJECT, TestGlobals.COLL, "doc-1", queuedGeneration);

        assertTrue(pendingIndexWrites.idsFor(TestGlobals.PROJECT, TestGlobals.COLL).contains("doc-1"),
                "the newer write's index update has not run yet, so its id must stay reconciled");
        assertFalse(markerPresent(), "a logged write's index work is covered by its log record, not a marker");
    }

    private void seed(String id, JsonBaseElement value) {
        final var object = new JsonObject();
        object.addProperty(Globals.PK_FIELD, id);
        object.add(FIELD, value);
        final var request = new SaveRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        request.setObject(object);
        processor.processMessage(request);
    }

    private static File collectionFolder() {
        return new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.PROJECT + Globals.FILE_SEPARATOR
                + TestGlobals.COLL);
    }

    private static File[] fieldIndexFiles() {
        final var prefix = TestGlobals.COLL + "-" + FIELD + "-";
        final var files = collectionFolder().listFiles((_, name) -> name.startsWith(prefix) && name.endsWith(".idx"));
        return files == null ? new File[0] : files;
    }

    private static void blockStringIndexRewrite() throws Exception {
        final var tempTarget = new File(collectionFolder(), TestGlobals.COLL + "-" + FIELD + "-String.idx.repair");
        assertTrue(tempTarget.mkdir());
        Files.writeString(new File(tempTarget, "occupied").toPath(), "x");
    }

    private int countActive() {
        final var request = new AggregateRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        request.setAggregationSteps(List.of(new FilterAggregationStep(
                new FieldOperator(FieldOperatorType.EQUALS, FIELD, new JsonString("active")))));
        final var response = (AggregateResponse) processor.processMessage(request);
        return response.getResults() == null ? 0 : response.getResults().size();
    }

    @Test
    public void test_a_failed_reindex_leaves_no_partial_index_and_answers_by_scan() throws Exception {
        seedStatuses();
        assertEquals(2, fieldIndexFiles().length);
        blockStringIndexRewrite();

        final var response = processor
                .processMessage(new ReindexRequest(TestGlobals.PROJECT, TestGlobals.COLL, List.of(FIELD)));

        assertNotEquals(OperationStatus.OK, response.getStatus());
        assertEquals(0, fieldIndexFiles().length, "a failed build must not leave some of the field's type files");
        assertEquals(2, countActive(), "a registered field with no index files must answer by scan");
    }
}
