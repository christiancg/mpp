package org.ccg.mpp.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.ccg.mpp.analyze.AnalyzeContext;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.ConfigKey;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.ejson.elements.JsonArray;
import org.ccg.mpp.ejson.elements.JsonBaseElement;
import org.ccg.mpp.ejson.elements.JsonNumber;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.req.AggregateRequest;
import org.ccg.mpp.ops.req.BulkSaveRequest;
import org.ccg.mpp.ops.req.CreateCollectionRequest;
import org.ccg.mpp.ops.req.CreateIndexRequest;
import org.ccg.mpp.ops.req.agg.BaseAggregationStep;
import org.ccg.mpp.ops.req.agg.FieldOperatorType;
import org.ccg.mpp.ops.req.agg.operators.FieldOperator;
import org.ccg.mpp.ops.req.agg.step.FilterAggregationStep;
import org.ccg.mpp.ops.req.agg.step.GroupByAggregationStep;
import org.ccg.mpp.ops.req.agg.step.SortAggregationStep;
import org.ccg.mpp.ops.resp.AggregateAnalyzeResponse;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class ScanPreferenceTest {
    private static final String COLL = "selectivity";
    private static final String SMALL = "selectivitySmall";
    private static final int DOCUMENTS = 5_000;
    private static final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final Configuration configuration = Configuration.getInstance();

    @BeforeAll
    static void seed() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
        seed(COLL, DOCUMENTS);
        seed(SMALL, 100);
    }

    private static void seed(String coll, int documents) {
        processor.processMessage(new CreateCollectionRequest(TestGlobals.PROJECT, coll));
        final var objects = new ArrayList<JsonObject>();
        for (var i = 0; i < documents; i++) {
            final var doc = new JsonObject();
            doc.add("_id", new JsonString("p" + i));
            doc.add("half", new JsonString(i % 2 == 0 ? "even" : "odd"));
            doc.add("num", new JsonNumber(i));
            objects.add(doc);
        }
        final var bulk = new BulkSaveRequest(TestGlobals.PROJECT, coll);
        bulk.setObjects(objects);
        assertEquals(OperationStatus.OK, processor.processMessage(bulk).getStatus());
        for (final var field : List.of("half", "num")) {
            assertEquals(OperationStatus.OK,
                    processor.processMessage(new CreateIndexRequest(TestGlobals.PROJECT, coll, field)).getStatus());
        }
    }

    @AfterEach
    void restorePercent() throws Exception {
        TestUtils.setPrivateField(configuration, "indexScanFallbackPercent",
                ConfigKey.INDEX_SCAN_FALLBACK_PERCENT.defaultValue());
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static FieldOperator field(FieldOperatorType type, String name, JsonBaseElement value) {
        return new FieldOperator(type, name, value);
    }

    @Test
    public void test_a_match_is_too_broad_only_past_the_percent_of_a_large_collection() throws Exception {
        assertFalse(ScanPreference.tooBroad(1_250, TestGlobals.PROJECT, COLL));
        assertTrue(ScanPreference.tooBroad(1_251, TestGlobals.PROJECT, COLL));
        assertFalse(ScanPreference.tooBroad(100, TestGlobals.PROJECT, SMALL));
        TestUtils.setPrivateField(configuration, "indexScanFallbackPercent", 100);
        assertFalse(ScanPreference.tooBroad(DOCUMENTS, TestGlobals.PROJECT, COLL));
    }

    @Test
    public void test_a_broad_equals_or_range_prefers_the_scan_without_building_the_id_set() throws Exception {
        final var analyze = new AnalyzeContext();
        try (var context = PipelineContext.of(0L, Long.MAX_VALUE, analyze)) {
            assertTrue(ScanPreference.prefersScan(field(FieldOperatorType.EQUALS, "half", new JsonString("even")),
                    TestGlobals.PROJECT, COLL, context));
            assertTrue(
                    ScanPreference.prefersScan(field(FieldOperatorType.GREATER_THAN_EQUALS, "num", new JsonNumber(100)),
                            TestGlobals.PROJECT, COLL, context));
            assertFalse(ScanPreference.prefersScan(field(FieldOperatorType.SMALLER_THAN, "num", new JsonNumber(100)),
                    TestGlobals.PROJECT, COLL, context));
            assertTrue(ScanPreference.prefersScan(field(FieldOperatorType.NOT_EQUALS, "half", new JsonString("x")),
                    TestGlobals.PROJECT, COLL, context));
            assertFalse(ScanPreference.prefersScan(field(FieldOperatorType.EQUALS, "_id", new JsonString("p1")),
                    TestGlobals.PROJECT, COLL, context));
            assertFalse(ScanPreference.prefersScan(field(FieldOperatorType.EQUALS, "absent", new JsonString("p1")),
                    TestGlobals.PROJECT, COLL, context));
            assertFalse(ScanPreference.prefersScan(field(FieldOperatorType.EQUALS, "half", new JsonNumber(1)),
                    TestGlobals.PROJECT, COLL, context));
            assertFalse(ScanPreference.prefersScan(field(FieldOperatorType.EQUALS, "half", new JsonString("even")),
                    TestGlobals.PROJECT, SMALL, context));
        }
        assertTrue(analyze.getIndexesUsed().contains("half"));
        TestUtils.setPrivateField(configuration, "indexScanFallbackPercent", 100);
        try (var context = PipelineContext.of(0L, Long.MAX_VALUE, null)) {
            assertFalse(ScanPreference.prefersScan(field(FieldOperatorType.EQUALS, "half", new JsonString("even")),
                    TestGlobals.PROJECT, COLL, context));
        }
    }

    @Test
    public void complementPastFallbackScans() throws Exception {
        final var mostNumbers = new JsonArray();
        for (var i = 0; i < 4_000; i++) {
            mostNumbers.add(new JsonNumber(i));
        }
        final var fewNumbers = new JsonArray();
        fewNumbers.add(new JsonNumber(1));
        fewNumbers.add(new JsonNumber(2));
        try (var context = PipelineContext.of(0L, Long.MAX_VALUE, null)) {
            assertTrue(ScanPreference.prefersScan(field(FieldOperatorType.NOT_IN, "num", fewNumbers),
                    TestGlobals.PROJECT, COLL, context));
            assertTrue(ScanPreference.prefersScan(field(FieldOperatorType.NOT_EQUALS, "num", new JsonNumber(7)),
                    TestGlobals.PROJECT, COLL, context));
            assertFalse(ScanPreference.prefersScan(field(FieldOperatorType.NOT_IN, "num", mostNumbers),
                    TestGlobals.PROJECT, COLL, context));
            assertFalse(ScanPreference.prefersScan(field(FieldOperatorType.NOT_EQUALS, "half", new JsonNumber(7)),
                    TestGlobals.PROJECT, COLL, context));
            assertFalse(ScanPreference.prefersScan(field(FieldOperatorType.NOT_IN, "num", new JsonArray()),
                    TestGlobals.PROJECT, COLL, context));
            assertFalse(ScanPreference.prefersScan(field(FieldOperatorType.NOT_EQUALS, "half", new JsonObject()),
                    TestGlobals.PROJECT, COLL, context));
        }
    }

    @Test
    public void test_a_group_by_prefers_the_scan_on_a_large_collection_cached_or_not() throws Exception {
        cache.getWholeCollection(TestGlobals.PROJECT, COLL);
        assertTrue(ScanPreference.groupByPrefersScan(TestGlobals.PROJECT, COLL));
        assertFalse(ScanPreference.groupByPrefersScan(TestGlobals.PROJECT, SMALL));
        TestUtils.setPrivateField(configuration, "indexScanFallbackPercent", 100);
        assertFalse(ScanPreference.groupByPrefersScan(TestGlobals.PROJECT, COLL));
    }

    @Test
    public void test_an_index_fetch_is_not_worth_it_on_a_large_cold_collection() throws Exception {
        cache.userCache().evictCollectionDocuments(TestGlobals.PROJECT, COLL);
        assertTrue(ScanPreference.indexFetchNotWorthIt(TestGlobals.PROJECT, COLL));
        assertFalse(ScanPreference.indexFetchNotWorthIt(TestGlobals.PROJECT, SMALL));
        TestUtils.setPrivateField(configuration, "indexScanFallbackPercent", 100);
        assertFalse(ScanPreference.indexFetchNotWorthIt(TestGlobals.PROJECT, COLL));
        TestUtils.setPrivateField(configuration, "indexScanFallbackPercent", 25);
        cache.getWholeCollection(TestGlobals.PROJECT, COLL);
        final var admitted = cache.holdsWholeCollection(TestGlobals.PROJECT, COLL);
        assertEquals(!admitted, ScanPreference.indexFetchNotWorthIt(TestGlobals.PROJECT, COLL));
    }

    private static List<String> sortedIds(List<BaseAggregationStep> steps) {
        final var request = new AggregateRequest(TestGlobals.PROJECT, COLL);
        request.setAnalyze(true);
        request.setAggregationSteps(steps);
        final var response = (AggregateAnalyzeResponse) processor.processMessage(request);
        assertEquals(OperationStatus.OK, response.getStatus(), response.getMessage());
        final var ids = new ArrayList<String>();
        for (final var row : response.getResults()) {
            final var group = row.get("group");
            if (group != null) {
                group.asJsonArray().forEach(member -> ids.add(row.get("half").asJsonString().getValue() + ":"
                        + member.asJsonObject().get("_id").asJsonString().getValue()));
            } else {
                ids.add(row.get("_id").asJsonString().getValue());
            }
        }
        return ids.stream().sorted().toList();
    }

    @Test
    public void test_broad_queries_answer_the_same_through_the_index_and_the_scan() throws Exception {
        final var notIn = new JsonArray();
        notIn.add(new JsonNumber(1));
        notIn.add(new JsonNumber(2));
        final List<List<BaseAggregationStep>> queries = List.of(
                List.of(new FilterAggregationStep(field(FieldOperatorType.EQUALS, "half", new JsonString("ODD")))),
                List.of(new FilterAggregationStep(field(FieldOperatorType.GREATER_THAN, "num", new JsonNumber(10)))),
                List.of(new FilterAggregationStep(field(FieldOperatorType.NOT_IN, "num", notIn))),
                List.of(new GroupByAggregationStep("half")), List.of(new SortAggregationStep("num", false)));
        for (final var query : queries) {
            cache.userCache().evictCollectionDocuments(TestGlobals.PROJECT, COLL);
            final var gated = sortedIds(query);
            TestUtils.setPrivateField(configuration, "indexScanFallbackPercent", 100);
            cache.userCache().evictCollectionDocuments(TestGlobals.PROJECT, COLL);
            final var indexed = sortedIds(query);
            TestUtils.setPrivateField(configuration, "indexScanFallbackPercent", 25);

            assertEquals(indexed, gated, query.toString());
            assertTrue(gated.size() > DOCUMENTS / 4, query.toString());
        }
    }
}
