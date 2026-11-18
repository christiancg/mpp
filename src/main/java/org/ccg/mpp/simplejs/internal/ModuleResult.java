package org.ccg.mpp.simplejs.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import org.ccg.mpp.simplejs.values.JsUndefined;
import org.ccg.mpp.simplejs.values.JsValue;

public final class ModuleResult {
    public JsValue last = JsUndefined.getInstance();
    public final Map<String, JsValue> namedExports = new LinkedHashMap<>();
    public JsValue exportDefault;
    public boolean hasReturn;
    public JsValue returnValue = JsUndefined.getInstance();
}
