package org.ccg.mpp.simplejs.values;

import org.ccg.mpp.ejson.elements.JsonBaseElement;

final class DeferredEJson extends JsValue {
    private final JsonBaseElement element;

    DeferredEJson(JsonBaseElement element) {
        this.element = element;
    }

    JsValue resolve() {
        return EJsonInterop.viewOrValue(element);
    }
}
