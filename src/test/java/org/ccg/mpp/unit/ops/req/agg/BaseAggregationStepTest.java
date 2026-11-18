package org.ccg.mpp.unit.ops.req.agg;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.ccg.mpp.ops.req.agg.AggregationStepType;
import org.ccg.mpp.ops.req.agg.BaseAggregationStep;
import org.junit.jupiter.api.Test;

public class BaseAggregationStepTest {
    @Test
    public void create_base_aggregation_step_with_valid_type() {
        AggregationStepType expectedType = AggregationStepType.FILTER;

        BaseAggregationStep step = new BaseAggregationStep(expectedType);

        assertEquals(expectedType, step.getType());
    }
}
