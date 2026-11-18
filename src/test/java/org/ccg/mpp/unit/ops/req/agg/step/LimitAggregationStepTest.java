package org.ccg.mpp.unit.ops.req.agg.step;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.ccg.mpp.ops.req.agg.AggregationStepType;
import org.ccg.mpp.ops.req.agg.step.LimitAggregationStep;
import org.junit.jupiter.api.Test;

public class LimitAggregationStepTest {
    @Test
    public void test_constructor_with_positive_limit() {
        int expectedLimit = 10;
        LimitAggregationStep limitStep = new LimitAggregationStep(expectedLimit);

        assertEquals(expectedLimit, limitStep.getLimit());
        assertEquals(AggregationStepType.LIMIT, limitStep.getType());
    }

    @Test
    public void test_constructor_with_zero_limit() {
        int expectedLimit = 0;
        LimitAggregationStep limitStep = new LimitAggregationStep(expectedLimit);

        assertEquals(expectedLimit, limitStep.getLimit());
        assertEquals(AggregationStepType.LIMIT, limitStep.getType());
    }

    @Test
    public void test_getters_and_setters() {
        int initialLimit = 5;
        LimitAggregationStep limitStep = new LimitAggregationStep(initialLimit);

        assertEquals(initialLimit, limitStep.getLimit());

        int newLimit = 15;
        limitStep.setLimit(newLimit);

        assertEquals(newLimit, limitStep.getLimit());
    }
}
