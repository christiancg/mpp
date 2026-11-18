package org.ccg.mpp.simplejs.elements;

public final class JsUndefined extends JsBaseElement {
    private static final JsUndefined instance = new JsUndefined();
    private JsUndefined() {
    }
    public static JsUndefined getInstance() {
        return instance;
    }
}
