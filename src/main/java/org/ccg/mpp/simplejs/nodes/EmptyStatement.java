package org.ccg.mpp.simplejs.nodes;

public class EmptyStatement extends Statement {

    @Override
    public NodeType getType() {
        return NodeType.EMPTY_STATEMENT;
    }
}
