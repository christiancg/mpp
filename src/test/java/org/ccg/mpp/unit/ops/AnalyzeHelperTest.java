package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.ccg.mpp.analyze.AnalyzeContext;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.ops.AnalyzeHelper;
import org.ccg.mpp.ops.req.AggregateRequest;
import org.ccg.mpp.ops.req.agg.ConjunctionOperatorType;
import org.ccg.mpp.ops.req.agg.FieldOperatorType;
import org.ccg.mpp.ops.req.agg.operators.ConjunctionOperator;
import org.ccg.mpp.ops.req.agg.operators.FieldOperator;
import org.ccg.mpp.ops.req.agg.step.DistinctAggregationStep;
import org.ccg.mpp.ops.req.agg.step.FilterAggregationStep;
import org.ccg.mpp.ops.req.agg.step.GroupByAggregationStep;
import org.ccg.mpp.ops.req.agg.step.JoinAggregationStep;
import org.ccg.mpp.ops.req.agg.step.SortAggregationStep;
import org.junit.jupiter.api.Test;

public class AnalyzeHelperTest {

    private static AggregateRequest request(org.ccg.mpp.ops.req.agg.BaseAggregationStep... steps) {
        final var req = new AggregateRequest("myProject", "myColl");
        req.setAnalyze(true);
        req.setAggregationSteps(List.of(steps));
        return req;
    }

    private static FilterAggregationStep filter(String field) {
        return new FilterAggregationStep(new FieldOperator(FieldOperatorType.EQUALS, field, new JsonString("x")));
    }

    @Test
    public void test_build_reports_documents_scanned_and_index_usage() {
        final var context = new AnalyzeContext();
        context.addScanned(7);
        context.addIndexUsed("status");
        context.addLock("myProject|myColl");
        context.addLock("myProject|myColl|status");

        final var result = AnalyzeHelper.build(request(filter("status")), context);

        assertTrue(result.isIndexUsed());
        assertEquals(List.of("status"), result.getIndexesUsed());
        assertEquals(7, result.getDocumentsScanned());
        assertTrue(result.getLocksAcquired().contains("myProject|myColl|status"));
        assertTrue(result.getSuggestions().isEmpty());
    }

    @Test
    public void test_build_suggests_index_when_none_used() {
        final var result = AnalyzeHelper.build(request(filter("name")), new AnalyzeContext());

        assertFalse(result.isIndexUsed());
        assertEquals(1, result.getSuggestions().size());
        final var suggestion = result.getSuggestions().getFirst();
        assertTrue(suggestion.startsWith("No index was used"));
        assertTrue(suggestion.contains("name"));
    }

    @Test
    public void test_build_suggests_moving_filter_when_not_first() {
        final var result = AnalyzeHelper.build(request(new SortAggregationStep("name", true), filter("status")),
                new AnalyzeContext());

        final var moveSuggestion = result.getSuggestions().stream().filter(s -> s.startsWith("FILTER step")).findFirst()
                .orElseThrow();
        assertTrue(moveSuggestion.contains("step 2"));
        assertTrue(moveSuggestion.contains("status"));
    }

    @Test
    public void test_build_no_filter_suggestion_when_filter_first() {
        final var context = new AnalyzeContext();
        context.addIndexUsed("status");
        final var result = AnalyzeHelper.build(request(filter("status"), new SortAggregationStep("name", true)),
                context);

        assertTrue(result.getSuggestions().stream().noneMatch(s -> s.startsWith("FILTER step")));
    }

    @Test
    public void test_build_collects_candidate_fields_from_all_step_types() {
        final var result = AnalyzeHelper.build(
                request(filter("f1"), new SortAggregationStep("f2", true), new GroupByAggregationStep("f3"),
                        new JoinAggregationStep("otherColl", "local", "f4", "as"), new DistinctAggregationStep("f5")),
                new AnalyzeContext());

        final var suggestion = result.getSuggestions().getFirst();
        for (var field : List.of("f1", "f2", "f3", "f4", "f5")) {
            assertTrue(suggestion.contains(field), "missing candidate field " + field);
        }
    }

    @Test
    public void test_build_handles_conjunction_filter_leaf_fields() {
        final var leaf1 = new FieldOperator(FieldOperatorType.EQUALS, "a", new JsonString("x"));
        final var leaf2 = new FieldOperator(FieldOperatorType.EQUALS, "b", new JsonString("y"));
        final var conjunction = new ConjunctionOperator(ConjunctionOperatorType.AND, List.of(leaf1, leaf2));
        final var result = AnalyzeHelper.build(request(new FilterAggregationStep(conjunction)), new AnalyzeContext());

        final var suggestion = result.getSuggestions().getFirst();
        assertTrue(suggestion.contains("a"));
        assertTrue(suggestion.contains("b"));
    }

    @Test
    public void test_build_empty_steps_reports_no_index_without_candidates() {
        final var req = new AggregateRequest("myProject", "myColl");
        req.setAnalyze(true);
        req.setAggregationSteps(List.of());
        final var result = AnalyzeHelper.build(req, new AnalyzeContext());
        assertEquals(List.of("No index was used for this query."), result.getSuggestions());
    }

    @Test
    public void test_build_handles_null_steps() {
        final var req = new AggregateRequest("myProject", "myColl");
        req.setAnalyze(true);
        final var result = AnalyzeHelper.build(req, new AnalyzeContext());
        assertNotNull(result.getSuggestions());
        assertTrue(result.getSuggestions().isEmpty());
    }
}
