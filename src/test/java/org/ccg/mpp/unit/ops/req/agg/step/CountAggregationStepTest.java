package org.ccg.mpp.unit.ops.req.agg.step;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.ccg.mpp.ops.req.agg.AggregationStepType;
import org.ccg.mpp.ops.req.agg.step.CountAggregationStep;
import org.junit.jupiter.api.Test;

public class CountAggregationStepTest {
    @Test
    public void test_constructor_sets_count_type() {
        CountAggregationStep step = new CountAggregationStep();

        assertEquals(AggregationStepType.COUNT, step.getType());
    }
}
