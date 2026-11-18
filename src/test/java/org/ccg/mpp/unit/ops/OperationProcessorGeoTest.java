package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.ccg.mpp.ejson.custom_types.JsonGeo;
import org.ccg.mpp.ejson.elements.JsonArray;
import org.ccg.mpp.ejson.elements.JsonNumber;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.req.AggregateRequest;
import org.ccg.mpp.ops.req.CreateCollectionRequest;
import org.ccg.mpp.ops.req.CreateIndexRequest;
import org.ccg.mpp.ops.req.SaveRequest;
import org.ccg.mpp.ops.req.agg.operators.CustomOperator;
import org.ccg.mpp.ops.req.agg.step.CountAggregationStep;
import org.ccg.mpp.ops.req.agg.step.FilterAggregationStep;
import org.ccg.mpp.ops.resp.AggregateAnalyzeResponse;
import org.ccg.mpp.ops.resp.AggregateResponse;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class OperationProcessorGeoTest {
    final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

    @BeforeAll
    static void setUpBeforeClass() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterAll
    public static void tearDownAll() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @Test
    public void test_distance_filter_without_index() {
        final var coll = "geoDistScan";
        seedCity(coll);

        final var response = aggregate(coll, distanceStep("SMALLER_THAN"));

        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(List.of("close", "near"), sortedIds(response.getResults()));
    }

    @Test
    public void test_distance_filter_with_index_reports_index_used() {
        final var coll = "geoDistIdx";
        seedCity(coll);
        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateIndexRequest(TestGlobals.DB, coll, "location")).getStatus());

        final var request = new AggregateRequest(TestGlobals.DB, coll);
        request.setAnalyze(true);
        request.setAggregationSteps(List.of(distanceStep("SMALLER_THAN")));
        final var response = (AggregateAnalyzeResponse) processor.processMessage(request);

        assertEquals(List.of("close", "near"), sortedIds(response.getResults()));
        assertTrue(response.getAnalyzeResult().isIndexUsed());
        assertTrue(response.getAnalyzeResult().getIndexesUsed().contains("location"));
    }

    // distance GREATER_THAN selects the complement (the far point) and cannot use the index (a box
    // cannot prune an "outside" set), so analyze reports no index used.
    @Test
    public void test_distance_greater_than_falls_back_to_scan() {
        final var coll = "geoDistFar";
        seedCity(coll);
        processor.processMessage(new CreateIndexRequest(TestGlobals.DB, coll, "location"));

        final var request = new AggregateRequest(TestGlobals.DB, coll);
        request.setAnalyze(true);
        request.setAggregationSteps(List.of(distanceStep("GREATER_THAN")));
        final var response = (AggregateAnalyzeResponse) processor.processMessage(request);

        assertEquals(List.of("far"), sortedIds(response.getResults()));
        assertFalse(response.getAnalyzeResult().isIndexUsed());
    }

    @Test
    public void test_within_filter_with_index() {
        final var coll = "geoWithin";
        seedCity(coll);
        processor.processMessage(new CreateIndexRequest(TestGlobals.DB, coll, "location"));

        final var response = aggregate(coll, withinStep());

        assertEquals(List.of("close", "near"), sortedIds(response.getResults()));
    }

    @Test
    public void test_count_after_distance_filter() {
        final var coll = "geoCount";
        seedCity(coll);
        processor.processMessage(new CreateIndexRequest(TestGlobals.DB, coll, "location"));

        final var request = new AggregateRequest(TestGlobals.DB, coll);
        request.setAggregationSteps(List.of(distanceStep("SMALLER_THAN"), new CountAggregationStep()));
        final var response = (AggregateResponse) processor.processMessage(request);

        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(2, response.getResults().getFirst().get("count").asJsonNumber().getValue().intValue());
    }

    private void seedCity(String coll) {
        processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, coll));
        saveGeo(coll, "near", "#geo(40.0,-74.0)");
        saveGeo(coll, "close", "#geo(40.001,-74.001)");
        saveGeo(coll, "far", "#geo(34.05,-118.24)");
    }

    private void saveGeo(String coll, String id, String geo) {
        final var save = new SaveRequest(TestGlobals.DB, coll);
        final var obj = new JsonObject();
        obj.add("_id", new JsonString(id));
        obj.add("location", new JsonGeo(geo));
        save.setObject(obj);
        assertEquals(OperationStatus.OK, processor.processMessage(save).getStatus());
    }

    private AggregateResponse aggregate(String coll, FilterAggregationStep step) {
        final var request = new AggregateRequest(TestGlobals.DB, coll);
        request.setAggregationSteps(List.of(step));
        return (AggregateResponse) processor.processMessage(request);
    }

    private static FilterAggregationStep distanceStep(String comparator) {
        final var target = new JsonGeo("#geo(40.0,-74.0)");
        final var args = new JsonObject();
        args.add("value", target);
        args.add("comparator", new JsonString(comparator));
        args.add("distance", new JsonNumber(1000));
        return new FilterAggregationStep(new CustomOperator("distance", "location", target, args));
    }

    private static FilterAggregationStep withinStep() {
        final var polygon = new JsonArray();
        polygon.add(new JsonGeo("#geo(39.9,-74.1)"));
        polygon.add(new JsonGeo("#geo(39.9,-73.9)"));
        polygon.add(new JsonGeo("#geo(40.1,-73.9)"));
        polygon.add(new JsonGeo("#geo(40.1,-74.1)"));
        final var args = new JsonObject();
        args.add("polygon", polygon);
        return new FilterAggregationStep(new CustomOperator("within", "location", null, args));
    }

    private static List<String> sortedIds(List<JsonObject> results) {
        return results.stream().map(o -> o.get("_id").asJsonString().getValue()).sorted().toList();
    }
}
