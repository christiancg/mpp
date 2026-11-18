package org.ccg.mpp.ops.req.agg.step.map;

import org.ccg.mpp.ops.req.agg.BaseOperator;
import org.ccg.mpp.ops.req.agg.mid_operators.BaseMidOperator;

public class AddFieldMapOperator extends MapOperator {
    private final BaseMidOperator operator;

    public AddFieldMapOperator(String fieldName, BaseOperator condition, BaseMidOperator operator) {
        super(MapOperationType.ADD_FIELD, fieldName, condition);
        this.operator = operator;
    }

    public BaseMidOperator getOperator() {
        return operator;
    }
}
