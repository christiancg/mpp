package org.ccg.mpp.simplejs.values;

import java.util.List;

@FunctionalInterface
public interface NativeConstructor {
    JsValue construct(JsValue thisArg, List<JsValue> args, JsValue newTarget);
}
