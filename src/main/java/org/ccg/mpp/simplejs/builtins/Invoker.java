package org.ccg.mpp.simplejs.builtins;

import java.util.List;
import org.ccg.mpp.simplejs.values.JsValue;

@FunctionalInterface
public interface Invoker {
    JsValue call(JsValue fn, JsValue thisArg, List<JsValue> args);
}
