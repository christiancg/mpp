package org.ccg.mpp.ops.req.agg.step;

import org.ccg.mpp.ops.req.agg.AggregationStepType;
import org.ccg.mpp.ops.req.agg.BaseAggregationStep;

public class GroupByAggregationStep extends BaseAggregationStep {
    private String fieldName;

    public GroupByAggregationStep(final String fieldName) {
        super(AggregationStepType.GROUP_BY);
        this.fieldName = fieldName;
    }

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }
}
