package org.ccg.mpp.simplejs.nodes;

public class SuperExpression extends Expression {

    @Override
    public NodeType getType() {
        return NodeType.SUPER_EXPRESSION;
    }
}
