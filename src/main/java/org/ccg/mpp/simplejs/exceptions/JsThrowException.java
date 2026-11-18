package org.ccg.mpp.simplejs.exceptions;

import org.ccg.mpp.simplejs.values.JsValue;

public class JsThrowException extends SimpleJsRuntimeException {
    private final transient JsValue value;

    public JsThrowException(JsValue value) {
        super("Uncaught (in script)");
        this.value = value;
    }

    public JsValue getValue() {
        return value;
    }
}
