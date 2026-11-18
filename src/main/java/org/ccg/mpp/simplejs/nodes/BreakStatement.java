package org.ccg.mpp.simplejs.nodes;

public class BreakStatement extends Statement {
    private final Identifier label;

    public BreakStatement(Identifier label) {
        this.label = label;
    }

    public Identifier getLabel() {
        return label;
    }

    @Override
    public NodeType getType() {
        return NodeType.BREAK_STATEMENT;
    }
}
