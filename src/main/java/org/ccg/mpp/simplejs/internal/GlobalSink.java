package org.ccg.mpp.simplejs.internal;

import org.ccg.mpp.simplejs.values.JsValue;

@FunctionalInterface
public interface GlobalSink {
    void declareBuiltin(String name, JsValue value);
}
