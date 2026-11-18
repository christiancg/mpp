package org.ccg.mpp.ops.req.agg.mid_operators;

public class BaseMidOperator {
    private final MidOperationType type;

    public BaseMidOperator(MidOperationType type) {
        this.type = type;
    }

    public MidOperationType getType() {
        return type;
    }
}
