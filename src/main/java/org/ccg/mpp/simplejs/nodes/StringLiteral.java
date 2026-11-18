package org.ccg.mpp.simplejs.nodes;

public class StringLiteral extends Expression {
    private final String value;
    private org.ccg.mpp.simplejs.values.JsString boxed;

    public StringLiteral(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public org.ccg.mpp.simplejs.values.JsString boxed() {
        var cached = boxed;
        if (cached == null) {
            cached = new org.ccg.mpp.simplejs.values.JsString(value);
            boxed = cached;
        }
        return cached;
    }

    @Override
    public NodeType getType() {
        return NodeType.STRING_LITERAL;
    }
}
