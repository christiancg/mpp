package org.ccg.mpp.simplejs.values;

import java.util.List;
import org.ccg.mpp.simplejs.internal.Coroutine;

@FunctionalInterface
public interface CallerAwareFunction {
    JsValue call(JsValue thisArg, List<JsValue> args, Coroutine caller);
}
