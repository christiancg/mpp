package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.ccg.mpp.config.ConfigKey;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.conn.ClientTracker;
import org.ccg.mpp.data.Transaction;
import org.ccg.mpp.ejson.elements.JsonNumber;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.TransactionOperationHelper;
import org.ccg.mpp.ops.admin.ReadPathHelper;
import org.ccg.mpp.ops.req.AggregateRequest;
import org.ccg.mpp.ops.req.BulkSaveRequest;
import org.ccg.mpp.ops.req.SaveRequest;
import org.ccg.mpp.ops.req.agg.ConjunctionOperatorType;
import org.ccg.mpp.ops.req.agg.FieldOperatorType;
import org.ccg.mpp.ops.req.agg.operators.ConjunctionOperator;
import org.ccg.mpp.ops.req.agg.operators.FieldOperator;
import org.ccg.mpp.ops.req.agg.step.FilterAggregationStep;
import org.ccg.mpp.ops.req.agg.step.LimitAggregationStep;
import org.ccg.mpp.ops.req.agg.step.SortAggregationStep;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class AggregateDeadlineOverlayTest {
    private static final int DOCUMENTS = 20_000;

    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final Configuration configuration = Configuration.getInstance();
    private UUID clientId;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
        final var objects = new ArrayList<JsonObject>();
        for (var i = 0; i < DOCUMENTS; i++) {
            final var document = new JsonObject();
            document.addProperty("_id", "doc-" + i);
            document.addProperty("n", i);
            objects.add(document);
        }
        final var bulk = new BulkSaveRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        bulk.setObjects(objects);
        assertEquals(OperationStatus.OK, IocContainer.get(OperationProcessor.class).processMessage(bulk).getStatus());
        clientId = clientTracker.registerForwardedClient("admin");
    }

    @AfterEach
    public void tearDown() throws Exception {
        TransactionOperationHelper.rollback(clientId);
        clientTracker.removeById(clientId);
        TestUtils.setPrivateField(configuration, "aggregateTimeoutMs", ConfigKey.AGGREGATE_TIMEOUT_MS.defaultValue());
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private Transaction transactionWithOneSave() throws Exception {
        TransactionOperationHelper.start(clientId, UUID.randomUUID(), 0);
        final var buffered = new SaveRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        final var document = new JsonObject();
        document.addProperty("_id", "buffered");
        document.addProperty("n", -1);
        buffered.setObject(document);
        buffered.set_id("buffered");
        final var transaction = clientTracker.getActiveTransaction(clientId);
        assertEquals(OperationStatus.OK, TransactionOperationHelper.bufferSave(buffered, transaction).getStatus());
        TestUtils.setPrivateField(configuration, "aggregateTimeoutMs", 1);
        return transaction;
    }

    private static FieldOperator missingIs(int value) {
        return new FieldOperator(FieldOperatorType.EQUALS, "missing", new JsonNumber(value));
    }

    @Test
    public void aFilterOverATransactionOverlayStopsAtTheDeadline() throws Exception {
        final var transaction = transactionWithOneSave();
        final var request = new AggregateRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        request.setAggregationSteps(List.of(new FilterAggregationStep(missingIs(1))));

        final var response = ReadPathHelper.processAggregateOperation(request, transaction);

        assertEquals("408-6", response.getErrorCode());
    }

    @Test
    public void test_an_unindexed_filter_source_in_a_transaction_obeys_the_deadline() throws Exception {
        final var transaction = transactionWithOneSave();
        final var request = new AggregateRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        request.setAggregationSteps(List.of(
                new FilterAggregationStep(
                        new ConjunctionOperator(ConjunctionOperatorType.OR, List.of(missingIs(1), missingIs(2)))),
                new LimitAggregationStep(1)));

        final var response = ReadPathHelper.processAggregateOperation(request, transaction);

        assertEquals("408-6", response.getErrorCode());
    }

    @Test
    public void test_a_sort_source_over_an_overlay_obeys_the_deadline() throws Exception {
        final var transaction = transactionWithOneSave();
        final var request = new AggregateRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        request.setAggregationSteps(List.of(new SortAggregationStep("n", false)));

        final var response = ReadPathHelper.processAggregateOperation(request, transaction);

        assertEquals("408-6", response.getErrorCode());
    }
}
