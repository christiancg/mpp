package org.ccg.mpp.ops.req.agg.step;

import org.ccg.mpp.ops.req.agg.AggregationStepType;
import org.ccg.mpp.ops.req.agg.BaseAggregationStep;

public class LimitAggregationStep extends BaseAggregationStep {
    private Integer limit;

    public LimitAggregationStep(final int limit) {
        super(AggregationStepType.LIMIT);
        this.limit = limit;
    }

    public Integer getLimit() {
        return limit;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }
}
