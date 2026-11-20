package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.management.UnixOperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.stream.Stream;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.ejson.elements.JsonNumber;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.req.AggregateRequest;
import org.ccg.mpp.ops.req.agg.BaseAggregationStep;
import org.ccg.mpp.ops.req.agg.FieldOperatorType;
import org.ccg.mpp.ops.req.agg.operators.FieldOperator;
import org.ccg.mpp.ops.req.agg.step.CountAggregationStep;
import org.ccg.mpp.ops.req.agg.step.DistinctAggregationStep;
import org.ccg.mpp.ops.req.agg.step.FilterAggregationStep;
import org.ccg.mpp.ops.req.agg.step.GroupByAggregationStep;
import org.ccg.mpp.ops.req.agg.step.JoinAggregationStep;
import org.ccg.mpp.ops.req.agg.step.LimitAggregationStep;
import org.ccg.mpp.ops.req.agg.step.ReduceAggregationStep;
import org.ccg.mpp.ops.req.agg.step.SortAggregationStep;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class AggregationStreamLifetimeTest {
    private static final int REPETITIONS = 200;
    private static final int ALLOWED_DESCRIPTOR_DRIFT = 8;
    private static final String FIELD = "score";

    private OperationProcessor processor;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
        TestUtils.createTestJoinCollection();
        processor = new OperationProcessor();
        assertNull(IocContainer.get(Cache.class).getAdminPageEntries(TestGlobals.PROJECT, TestGlobals.COLL),
                "the collection must have no page metadata, or the scan never takes the folder-scan fallback"
                        + " that holds an open directory handle");
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.standardTearDown();
    }

    private static Stream<Arguments> pipelines() {
        return Stream.of(
                Arguments.of("sort_over_an_unwritten_collection_does_not_leak_descriptors",
                        List.of(new SortAggregationStep(FIELD, true))),
                Arguments.of("bounded_sort_over_an_unwritten_collection_does_not_leak_descriptors",
                        List.of(new SortAggregationStep(FIELD, true), new LimitAggregationStep(2))),
                Arguments.of("group_by_over_an_unwritten_collection_does_not_leak_descriptors",
                        List.of(new GroupByAggregationStep(FIELD))),
                Arguments.of("join_over_an_unwritten_collection_does_not_leak_descriptors",
                        List.of(new JoinAggregationStep(TestGlobals.JOIN_COLL, FIELD, FIELD, "joined"))),
                Arguments.of("count_after_an_unindexed_filter_does_not_leak_descriptors",
                        List.of(unindexedFilter(), new CountAggregationStep())),
                Arguments.of("reduce_over_an_unwritten_collection_does_not_leak_descriptors",
                        List.of(new ReduceAggregationStep("export default (acc, doc) => acc + doc.score;",
                                new JsonNumber(0), "total"))),
                Arguments.of("filter_and_distinct_stay_leak_free",
                        List.of(unindexedFilter(), new DistinctAggregationStep(FIELD))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pipelines")
    public void a_pipeline_over_an_unwritten_collection_does_not_leak_descriptors(String name,
            List<BaseAggregationStep> steps) {
        assertNoDescriptorLeak(steps);
    }

    private static FilterAggregationStep unindexedFilter() {
        return new FilterAggregationStep(
                new FieldOperator(FieldOperatorType.NOT_EQUALS, FIELD, new JsonString("absent")));
    }

    private void assertNoDescriptorLeak(List<BaseAggregationStep> steps) {
        final var operatingSystem = ManagementFactory.getOperatingSystemMXBean();
        assumeTrue(operatingSystem instanceof UnixOperatingSystemMXBean,
                "open descriptor counts are only readable through the Unix operating system MXBean");
        final var unix = (UnixOperatingSystemMXBean) operatingSystem;
        aggregate(steps);
        final var before = unix.getOpenFileDescriptorCount();
        for (var i = 0; i < REPETITIONS; i++) {
            aggregate(steps);
        }
        final var leaked = unix.getOpenFileDescriptorCount() - before;
        assertTrue(leaked <= ALLOWED_DESCRIPTOR_DRIFT, REPETITIONS + " aggregations leaked " + leaked
                + " file descriptors; a blocking step is not closing the stream it consumed");
    }

    private void aggregate(List<BaseAggregationStep> steps) {
        final var request = new AggregateRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        request.setAggregationSteps(steps);
        final var response = processor.processMessage(request);
        assertTrue(response.getStatus() == OperationStatus.OK || response.getStatus() == OperationStatus.NOT_FOUND,
                "the pipeline must really run, or the descriptor count proves nothing: " + response.getStatus() + " "
                        + response.getErrorCode());
    }
}
