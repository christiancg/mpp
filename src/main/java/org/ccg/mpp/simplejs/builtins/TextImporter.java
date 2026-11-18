package org.ccg.mpp.simplejs.builtins;

import org.ccg.mpp.simplejs.internal.Coroutine;
import org.ccg.mpp.simplejs.values.JsValue;

@FunctionalInterface
public interface TextImporter {
    JsValue importText(String moduleId, String source, Coroutine importer);
}
