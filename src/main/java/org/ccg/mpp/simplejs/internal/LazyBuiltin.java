package org.ccg.mpp.simplejs.internal;

import java.util.function.Supplier;
import org.ccg.mpp.simplejs.values.JsValue;

public final class LazyBuiltin {
    private Supplier<? extends JsValue> factory;
    private JsValue value;

    public LazyBuiltin(Supplier<? extends JsValue> factory) {
        this.factory = factory;
    }

    public JsValue get() {
        final var pending = factory;
        if (pending != null) {
            factory = null;
            value = pending.get();
        }
        return value;
    }
}
