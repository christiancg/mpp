package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.req.AggregateRequest;
import org.ccg.mpp.ops.req.CreateCollectionRequest;
import org.ccg.mpp.ops.req.CreateIndexRequest;
import org.ccg.mpp.ops.req.DropIndexRequest;
import org.ccg.mpp.ops.req.FindByIdRequest;
import org.ccg.mpp.ops.req.ReindexRequest;
import org.ccg.mpp.ops.req.SaveRequest;
import org.ccg.mpp.ops.req.agg.FieldOperatorType;
import org.ccg.mpp.ops.req.agg.operators.FieldOperator;
import org.ccg.mpp.ops.req.agg.step.FilterAggregationStep;
import org.ccg.mpp.ops.resp.AggregateAnalyzeResponse;
import org.ccg.mpp.ops.resp.OperationResponse;
import org.ccg.mpp.ops.resp.ReindexResponse;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class OperationProcessorAdminTest {
    final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

    @BeforeAll
    static void setUpBeforeClass() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
    }

    @AfterAll
    public static void tearDownAll() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @Test
    public void test_aggregation_with_analyze_reports_index_used() {
        final var coll = "analyzeIndexColl";
        processor.processMessage(new CreateCollectionRequest(TestGlobals.PROJECT, coll));
        final var saveRequest = new SaveRequest(TestGlobals.PROJECT, coll);
        final var obj = new JsonObject();
        obj.add("_id", new JsonString("a1"));
        obj.add("status", new JsonString("active"));
        saveRequest.setObject(obj);
        processor.processMessage(saveRequest);
        // Index creation is synchronous, so the index is available to the next aggregation.
        processor.processMessage(new CreateIndexRequest(TestGlobals.PROJECT, coll, "status"));

        final var aggregateRequest = new AggregateRequest(TestGlobals.PROJECT, coll);
        aggregateRequest.setAnalyze(true);
        aggregateRequest.setAggregationSteps(List.of(new FilterAggregationStep(
                new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active")))));

        final var response = (AggregateAnalyzeResponse) processor.processMessage(aggregateRequest);

        final var analyzeResult = response.getAnalyzeResult();
        assertTrue(analyzeResult.isIndexUsed());
        assertTrue(analyzeResult.getIndexesUsed().contains("status"));
        assertTrue(analyzeResult.getLocksAcquired()
                .contains(Cache.getCollectionIdentifier(TestGlobals.PROJECT, coll) + "|status"));
    }

    @Test
    public void test_aggregation_with_analyze_no_index_suggests_creation() {
        final var coll = "analyzeNoIndexColl";
        processor.processMessage(new CreateCollectionRequest(TestGlobals.PROJECT, coll));
        final var saveRequest = new SaveRequest(TestGlobals.PROJECT, coll);
        final var obj = new JsonObject();
        obj.add("_id", new JsonString("a1"));
        obj.add("notIndexed", new JsonString("v"));
        saveRequest.setObject(obj);
        processor.processMessage(saveRequest);

        final var aggregateRequest = new AggregateRequest(TestGlobals.PROJECT, coll);
        aggregateRequest.setAnalyze(true);
        aggregateRequest.setAggregationSteps(List.of(new FilterAggregationStep(
                new FieldOperator(FieldOperatorType.EQUALS, "notIndexed", new JsonString("v")))));

        final var response = (AggregateAnalyzeResponse) processor.processMessage(aggregateRequest);

        final var analyzeResult = response.getAnalyzeResult();
        assertFalse(analyzeResult.isIndexUsed());
        assertTrue(analyzeResult.getSuggestions().stream()
                .anyMatch(s -> s.startsWith("No index was used") && s.contains("notIndexed")));
    }

    @Test
    public void test_find_by_id_records_pk_index_access() {
        SaveRequest saveRequest = new SaveRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString("usage-id-2"));
        saveRequest.setObject(obj);
        processor.processMessage(saveRequest);
        final var mm = IocContainer.get(org.ccg.mpp.cache.MemoryManagement.class);
        final var before = mm.getCounter(org.ccg.mpp.cache.AccessKind.PK_INDEX, TestGlobals.PROJECT, TestGlobals.COLL,
                null);
        final var beforeCount = before == null ? 0L : before.getAccessCount();
        FindByIdRequest request = new FindByIdRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        request.set_id("usage-id-2");
        processor.processMessage(request);
        final var after = mm.getCounter(org.ccg.mpp.cache.AccessKind.PK_INDEX, TestGlobals.PROJECT, TestGlobals.COLL,
                null);
        assertNotNull(after);
        assertTrue(after.getAccessCount() > beforeCount);
    }

    @Test
    public void test_reindex_all_fields_rebuilds_registered_indexes() {
        processor.processMessage(new CreateIndexRequest(TestGlobals.PROJECT, TestGlobals.COLL, "reindexField"));
        try {
            ReindexRequest request = new ReindexRequest(TestGlobals.PROJECT, TestGlobals.COLL, null);
            ReindexResponse response = (ReindexResponse) processor.processMessage(request);
            assertEquals(OperationStatus.OK, response.getStatus());
            assertTrue(response.getRebuiltFields().contains("reindexField"));
        } finally {
            processor.processMessage(new DropIndexRequest(TestGlobals.PROJECT, TestGlobals.COLL, "reindexField"));
        }
    }

    @Test
    public void test_reindex_specific_field_rebuilds_only_that_field() {
        processor.processMessage(new CreateIndexRequest(TestGlobals.PROJECT, TestGlobals.COLL, "reindexFieldA"));
        processor.processMessage(new CreateIndexRequest(TestGlobals.PROJECT, TestGlobals.COLL, "reindexFieldB"));
        try {
            ReindexRequest request = new ReindexRequest(TestGlobals.PROJECT, TestGlobals.COLL,
                    List.of("reindexFieldA"));
            ReindexResponse response = (ReindexResponse) processor.processMessage(request);
            assertEquals(OperationStatus.OK, response.getStatus());
            assertEquals(List.of("reindexFieldA"), response.getRebuiltFields());
        } finally {
            processor.processMessage(new DropIndexRequest(TestGlobals.PROJECT, TestGlobals.COLL, "reindexFieldA"));
            processor.processMessage(new DropIndexRequest(TestGlobals.PROJECT, TestGlobals.COLL, "reindexFieldB"));
        }
    }

    @Test
    public void test_reindex_unknown_field_returns_error() {
        ReindexRequest request = new ReindexRequest(TestGlobals.PROJECT, TestGlobals.COLL, List.of("noSuchIndex"));
        OperationResponse response = processor.processMessage(request);
        assertEquals(OperationStatus.NOT_FOUND, response.getStatus());
        assertEquals("404-6", response.getErrorCode());
        assertTrue(response.getMessage().contains("noSuchIndex"));
    }
}
