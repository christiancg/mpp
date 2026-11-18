package org.ccg.mpp.ops.req.agg.step;

import org.ccg.mpp.ops.req.agg.AggregationStepType;
import org.ccg.mpp.ops.req.agg.BaseAggregationStep;

public class DistinctAggregationStep extends BaseAggregationStep {
    private String fieldName;

    public DistinctAggregationStep(final String fieldName) {
        super(AggregationStepType.DISTINCT);
        this.fieldName = fieldName;
    }

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }
}
