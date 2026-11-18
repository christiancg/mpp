package org.ccg.mpp.ops.req.agg.step;

import org.ccg.mpp.ops.req.agg.AggregationStepType;
import org.ccg.mpp.ops.req.agg.BaseAggregationStep;

public class CountAggregationStep extends BaseAggregationStep {
    public CountAggregationStep() {
        super(AggregationStepType.COUNT);
    }
}
