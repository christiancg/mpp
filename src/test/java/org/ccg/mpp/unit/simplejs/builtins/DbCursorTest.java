package org.ccg.mpp.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.ccg.mpp.ejson.elements.JsonArray;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.simplejs.SimpleJs;
import org.ccg.mpp.simplejs.exceptions.JsThrowException;
import org.ccg.mpp.simplejs.host.ResourceLimits;
import org.ccg.mpp.simplejs.host.ScriptResult;
import org.ccg.mpp.simplejs.host.SimpleHostBindings;
import org.ccg.mpp.simplejs.values.JsObject;
import org.ccg.mpp.simplejs.values.JsString;
import org.ccg.mpp.unit.simplejs.host.FakeProjectAccess;
import org.junit.jupiter.api.Test;

public class DbCursorTest {
    private static final String SORT_STEP = "[{ type: 'SORT', fieldName: '_id', ascending: true }]";

    private static final class PagingProject extends FakeProjectAccess {
        final List<JsonArray> pipelines = new ArrayList<>();
        private final List<JsonObject> documents = new ArrayList<>();
        private int failFromBatch = Integer.MAX_VALUE;
        private String padding = "";
        private boolean pipelineIsOrdered = true;
        private Predicate<JsonArray> ordering = _ -> pipelineIsOrdered;
        private int unorderedCut = -1;

        private PagingProject(int total) {
            for (var i = 0; i < total; i++) {
                final var document = new JsonObject();
                document.add("_id", new JsonString("d" + i));
                documents.add(document);
            }
        }

        @Override
        public List<JsonObject> aggregate(String db, String coll, JsonArray pipeline) {
            calls.add("aggregate:" + db + "/" + coll + "/" + pipeline.size());
            pipelines.add(pipeline);
            if (pipelines.size() >= failFromBatch) {
                final var error = new JsObject();
                error.set("name", new JsString("Error"));
                error.set("message", new JsString("collection was dropped"));
                throw new JsThrowException(error);
            }
            final var skip = value(pipeline, pipeline.size() - 2, "skip");
            final var limit = value(pipeline, pipeline.size() - 1, "limit");
            final var page = new ArrayList<JsonObject>();
            for (var i = skip; i < Math.min(skip + limit, documents.size()); i++) {
                final var document = documents.get(i);
                if (!padding.isEmpty()) {
                    document.add("payload", new JsonString(padding));
                }
                page.add(document);
            }
            return page;
        }

        @Override
        public boolean ordersResults(String db, String coll, JsonArray pipeline) {
            return ordering.test(pipeline);
        }

        @Override
        public int firstUnorderedCut(String db, String coll, JsonArray pipeline) {
            return unorderedCut;
        }

        private static int value(JsonArray pipeline, int index, String field) {
            return pipeline.get(index).asJsonObject().get(field).asJsonNumber().asInteger();
        }
    }

    private static ResourceLimits limits(long memory, int batchSize, int maxBatchSize) {
        return new ResourceLimits(-1, -1, -1, true, false, List.of(), -1, -1, false, false,
                ResourceLimits.DEFAULT_MAX_MODULE_DEPTH, ResourceLimits.DEFAULT_MAX_LOG_LINES,
                ResourceLimits.DEFAULT_MAX_LOG_LINE_CHARS, memory, -1, batchSize, maxBatchSize, 8,
                ResourceLimits.UNLIMITED_ARRAY_LENGTH);
    }

    private static ScriptResult run(PagingProject project, String source, ResourceLimits limits) {
        return new SimpleJs().run(source, new SimpleHostBindings(new JsonObject(), project, null, limits));
    }

    private static ScriptResult run(PagingProject project, String source) {
        return run(project, source, limits(-1, 500, 5000));
    }

    private static String walk(String options) {
        return """
                import db from "db";
                const seen = [];
                for (const doc of db.cursor('d', 'c', %s, %s)) {
                    seen.push(doc._id);
                }
                return seen;
                """.formatted(SORT_STEP, options);
    }

    private static List<String> ids(ScriptResult result) {
        assertFalse(result.isError(), result.getErrorMessage());
        final var ids = new ArrayList<String>();
        for (final var element : result.getValue().asJsonArray()) {
            ids.add(element.asJsonString().getValue());
        }
        return ids;
    }

    @Test
    public void test_iterates_every_document_across_batches() {
        final var project = new PagingProject(5);
        final var seen = ids(run(project, walk("{ batchSize: 2 }")));
        assertEquals(List.of("d0", "d1", "d2", "d3", "d4"), seen);
        assertEquals(3, project.pipelines.size());
    }

    @Test
    public void test_stops_on_a_short_batch() {
        final var project = new PagingProject(3);
        assertEquals(3, ids(run(project, walk("{ batchSize: 2 }"))).size());
        assertEquals(2, project.pipelines.size());
    }

    @Test
    public void test_appends_skip_and_limit_steps() {
        final var project = new PagingProject(5);
        ids(run(project, walk("{ batchSize: 2 }")));
        for (var batch = 0; batch < project.pipelines.size(); batch++) {
            final var pipeline = project.pipelines.get(batch);
            assertEquals(3, pipeline.size());
            assertEquals("SORT", pipeline.get(0).asJsonObject().get("type").asJsonString().getValue());
            final var skip = pipeline.get(1).asJsonObject();
            assertEquals("SKIP", skip.get("type").asJsonString().getValue());
            assertEquals(batch * 2, skip.get("skip").asJsonNumber().asInteger());
            final var limit = pipeline.get(2).asJsonObject();
            assertEquals("LIMIT", limit.get("type").asJsonString().getValue());
            assertEquals(2, limit.get("limit").asJsonNumber().asInteger());
        }
    }

    @Test
    public void test_does_not_mutate_the_caller_pipeline() {
        final var project = new PagingProject(3);
        final var result = run(project, """
                import db from "db";
                const steps = %s;
                for (const doc of db.cursor('d', 'c', steps, { batchSize: 2 })) {
                }
                return steps.length;
                """.formatted(SORT_STEP));
        assertFalse(result.isError(), result.getErrorMessage());
        assertEquals(1, result.getValue().asJsonNumber().asInteger());
    }

    @Test
    public void test_clamps_batch_size_to_the_configured_maximum() {
        final var project = new PagingProject(5);
        ids(run(project, walk("{ batchSize: 1000 }"), limits(-1, 2, 2)));
        assertEquals(2, project.pipelines.getFirst().get(2).asJsonObject().get("limit").asJsonNumber().asInteger());
    }

    @Test
    public void test_uses_the_configured_default_batch_size() {
        final var project = new PagingProject(1);
        ids(run(project, walk("undefined"), limits(-1, 7, 5000)));
        assertEquals(7, project.pipelines.getFirst().get(2).asJsonObject().get("limit").asJsonNumber().asInteger());
    }

    @Test
    public void test_rejects_non_positive_batch_size() {
        for (final var batchSize : List.of("0", "-1", "NaN", "'nope'")) {
            final var project = new PagingProject(3);
            final var result = run(project, walk("{ batchSize: " + batchSize + " }"));
            assertTrue(result.isError(), "batchSize " + batchSize + " must be rejected");
            assertEquals("RangeError", result.getErrorName());
            assertTrue(project.pipelines.isEmpty(), "nothing must be read for " + batchSize);
        }
    }

    @Test
    public void test_accepts_a_numeric_string_batch_size() {
        final var project = new PagingProject(3);
        assertEquals(3, ids(run(project, walk("{ batchSize: '2' }"))).size());
    }

    @Test
    public void test_empty_first_batch_yields_no_iterations() {
        final var project = new PagingProject(0);
        assertEquals(List.of(), ids(run(project, walk("{ batchSize: 2 }"))));
        assertEquals(1, project.pipelines.size());
    }

    @Test
    public void test_supports_spread_and_iterator_helpers() {
        final var project = new PagingProject(5);
        final var spread = run(project, """
                import db from "db";
                return [...db.cursor('d', 'c', %s, { batchSize: 2 })].map(d => d._id);
                """.formatted(SORT_STEP));
        assertEquals(List.of("d0", "d1", "d2", "d3", "d4"), ids(spread));

        final var helper = new PagingProject(5);
        final var taken = run(helper, """
                import db from "db";
                return db.cursor('d', 'c', %s, { batchSize: 2 }).take(2).toArray().map(d => d._id);
                """.formatted(SORT_STEP));
        assertEquals(List.of("d0", "d1"), ids(taken));
        assertEquals(1, helper.pipelines.size(), "take(2) must not fetch a second batch");
    }

    @Test
    public void test_charges_each_document_as_it_is_converted() {
        final var project = new PagingProject(200);
        project.padding = "x".repeat(512);
        final var result = run(project, walk("{ batchSize: 50 }"), limits(8192, 500, 5000));
        assertTrue(result.isError());
        assertEquals("ScriptMemoryError", result.getErrorName());
    }

    @Test
    public void test_a_drained_batch_is_refunded() {
        final var project = new PagingProject(200);
        project.padding = "x".repeat(512);
        assertEquals(200, ids(run(project, walk("{ batchSize: 5 }"), limits(65_536, 500, 5000))).size());
    }

    // The reported peak is a high-water mark, not the closing balance: a fully drained walk credits almost
    // everything back, so a peak derived at the end would read as ~0.
    @Test
    public void test_peak_memory_survives_the_refund() {
        final var project = new PagingProject(200);
        project.padding = "x".repeat(512);
        final var result = run(project, walk("{ batchSize: 5 }"), limits(65_536, 500, 5000));
        assertFalse(result.isError());
        assertTrue(result.getMetrics().peakMemoryBytes() > 512,
                "expected a peak above one document, got " + result.getMetrics().peakMemoryBytes());
        assertTrue(result.getMetrics().peakMemoryBytes() <= 65_536, "the peak may never exceed the budget");
    }

    @Test
    public void test_error_from_aggregate_surfaces_as_a_catchable_error() {
        final var project = new PagingProject(5);
        project.failFromBatch = 2;
        final var result = run(project, """
                import db from "db";
                const seen = [];
                try {
                    for (const doc of db.cursor('d', 'c', %s, { batchSize: 2 })) {
                        seen.push(doc._id);
                    }
                } catch (e) {
                    return e.message + " after " + seen.length;
                }
                return "no error";
                """.formatted(SORT_STEP));
        assertFalse(result.isError(), result.getErrorMessage());
        assertEquals("collection was dropped after 2", result.getValue().asJsonString().getValue());
    }

    @Test
    public void test_abandoned_cursor_leaves_no_state_behind() {
        final var project = new PagingProject(5);
        final var result = run(project, """
                import db from "db";
                for (const doc of db.cursor('d', 'c', %s, { batchSize: 2 })) {
                    break;
                }
                return "done";
                """.formatted(SORT_STEP));
        assertFalse(result.isError(), result.getErrorMessage());
        assertEquals(List.of("aggregate:d/c/3"), project.calls);
    }

    @Test
    public void test_requires_an_array_of_steps() {
        final var project = new PagingProject(1);
        final var result = run(project, "import db from \"db\"; db.cursor('d', 'c', 'nope');");
        assertTrue(result.isError());
        assertEquals("TypeError", result.getErrorName());
    }

    private static String firstStepType(JsonArray pipeline) {
        return pipeline.get(0).asJsonObject().get("type").asJsonString().getValue();
    }

    @Test
    public void test_a_cursor_over_an_unordered_pipeline_pages_in_id_order() {
        final var project = new PagingProject(5);
        project.pipelineIsOrdered = false;

        assertEquals(List.of("d0", "d1", "d2", "d3", "d4"), ids(run(project, walk("{ batchSize: 2 }"))));

        for (final var pipeline : project.pipelines) {
            final var sort = pipeline.get(pipeline.size() - 3).asJsonObject();
            assertEquals("SORT", sort.get("type").asJsonString().getValue(),
                    "each batch must page over the same order, so a SORT on _id precedes SKIP and LIMIT");
            assertEquals("_id", sort.get("fieldName").asJsonString().getValue());
            assertTrue(sort.get("ascending").asJsonBoolean().getValue());
        }
    }

    @Test
    public void test_a_cursor_over_an_ordered_pipeline_adds_no_sort() {
        final var project = new PagingProject(3);

        ids(run(project, walk("{ batchSize: 2 }")));

        for (final var pipeline : project.pipelines) {
            assertEquals(3, pipeline.size(), "the caller's own SORT plus SKIP and LIMIT, and nothing else");
            assertEquals("SORT", firstStepType(pipeline));
        }
    }

    private static String walkOver(String pipeline) {
        return """
                import db from "db";
                const seen = [];
                for (const doc of db.cursor('d', 'c', %s, { batchSize: 2 })) {
                    seen.push(doc._id);
                }
                return seen;
                """.formatted(pipeline);
    }

    private static List<String> stepTypes(JsonArray pipeline) {
        final var types = new ArrayList<String>();
        for (final var step : pipeline) {
            types.add(step.asJsonObject().get("type").asJsonString().getValue());
        }
        return types;
    }

    private static void assertSortsById(JsonArray pipeline, int index) {
        final var sort = pipeline.get(index).asJsonObject();
        assertEquals("SORT", sort.get("type").asJsonString().getValue());
        assertEquals("_id", sort.get("fieldName").asJsonString().getValue());
        assertTrue(sort.get("ascending").asJsonBoolean().getValue());
    }

    @Test
    public void test_a_cursor_anchors_the_order_before_an_unordered_limit() {
        final var project = new PagingProject(3);
        project.unorderedCut = 0;
        project.ordering = pipeline -> "SORT".equals(firstStepType(pipeline));

        ids(run(project, walkOver("[{ type: 'LIMIT', limit: 3 }]")));

        assertFalse(project.pipelines.isEmpty());
        for (final var pipeline : project.pipelines) {
            assertEquals(List.of("SORT", "LIMIT", "SKIP", "LIMIT"), stepTypes(pipeline),
                    "the LIMIT must pick its rows from a defined order, or each batch pages a different subset");
            assertSortsById(pipeline, 0);
        }
    }

    @Test
    public void test_a_cursor_keeps_the_trailing_sort_when_the_anchored_pipeline_is_still_unordered() {
        final var project = new PagingProject(3);
        project.unorderedCut = 1;
        project.ordering = _ -> false;

        ids(run(project, walkOver(
                "[{ type: 'FILTER', operator: { type: 'FIELD', fieldOperatorType: 'EQUALS', field: 'a', value: 1 } },"
                        + " { type: 'LIMIT', limit: 3 }, { type: 'DISTINCT', fieldName: 'a' }]")));

        assertFalse(project.pipelines.isEmpty());
        for (final var pipeline : project.pipelines) {
            assertEquals(List.of("FILTER", "SORT", "LIMIT", "DISTINCT", "SORT", "SKIP", "LIMIT"), stepTypes(pipeline));
            assertSortsById(pipeline, 1);
            assertSortsById(pipeline, 4);
        }
    }

    @Test
    public void test_a_cursor_with_no_unordered_cut_leaves_the_pipeline_as_given() {
        final var project = new PagingProject(3);

        ids(run(project, walkOver("[{ type: 'LIMIT', limit: 3 }]")));

        for (final var pipeline : project.pipelines) {
            assertEquals(List.of("LIMIT", "SKIP", "LIMIT"), stepTypes(pipeline));
        }
    }
}
