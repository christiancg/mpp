package org.ccg.mpp.simplejs.nodes;

public class YieldExpression extends Expression {
    private final Expression argument;
    private final boolean delegate;

    public YieldExpression(Expression argument, boolean delegate) {
        this.argument = argument;
        this.delegate = delegate;
    }

    public Expression getArgument() {
        return argument;
    }

    public boolean isDelegate() {
        return delegate;
    }

    @Override
    public NodeType getType() {
        return NodeType.YIELD_EXPRESSION;
    }
}
