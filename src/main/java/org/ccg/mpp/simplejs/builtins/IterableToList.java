package org.ccg.mpp.simplejs.builtins;

import java.util.List;
import org.ccg.mpp.simplejs.values.JsValue;

@FunctionalInterface
public interface IterableToList {
    List<JsValue> drain(JsValue iterable);
}
