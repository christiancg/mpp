package org.ccg.mpp.ops.req.agg.mid_operators;

public class ScriptMidOperator extends BaseMidOperator {
    private String source;

    public ScriptMidOperator(String source) {
        super(MidOperationType.SCRIPT);
        this.source = source;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}
