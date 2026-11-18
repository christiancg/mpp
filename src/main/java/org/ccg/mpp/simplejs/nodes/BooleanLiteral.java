package org.ccg.mpp.simplejs.nodes;

public class BooleanLiteral extends Expression {
    private final boolean value;

    public BooleanLiteral(boolean value) {
        this.value = value;
    }

    public boolean getValue() {
        return value;
    }

    @Override
    public NodeType getType() {
        return NodeType.BOOLEAN_LITERAL;
    }
}
