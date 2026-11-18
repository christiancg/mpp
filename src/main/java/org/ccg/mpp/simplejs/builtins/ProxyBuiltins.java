package org.ccg.mpp.simplejs.builtins;

import java.util.List;
import org.ccg.mpp.simplejs.exceptions.TypeErrorException;
import org.ccg.mpp.simplejs.internal.interpreter.InterpreterUtils;
import org.ccg.mpp.simplejs.values.JsNativeFunction;
import org.ccg.mpp.simplejs.values.JsObject;
import org.ccg.mpp.simplejs.values.JsProxy;
import org.ccg.mpp.simplejs.values.JsUndefined;
import org.ccg.mpp.simplejs.values.JsValue;

public final class ProxyBuiltins {
    private ProxyBuiltins() {
    }

    public static JsNativeFunction create() {
        final var proxy = new JsNativeFunction("Proxy", (thisArg, args, newTarget) -> {
            if (!NewTargetSupport.isConstructCall(newTarget) && !(thisArg instanceof JsObject)) {
                throw new TypeErrorException("Constructor Proxy requires 'new'");
            }
            return construct(args);
        });
        proxy.setProperty("revocable", new JsNativeFunction("revocable", (_, args) -> revocable(args)));
        return proxy;
    }

    private static JsProxy construct(List<JsValue> args) {
        final var target = args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
        final var handler = args.size() > 1 ? args.get(1) : JsUndefined.getInstance();
        if (!InterpreterUtils.isObjectLike(target) || !InterpreterUtils.isObjectLike(handler)) {
            throw new TypeErrorException("Cannot create proxy with a non-object as target or handler");
        }
        return new JsProxy(target, handler);
    }

    private static JsValue revocable(List<JsValue> args) {
        final var proxy = construct(args);
        final var result = new JsObject();
        result.set("proxy", proxy);
        result.set("revoke", new JsNativeFunction("", (_, _) -> {
            proxy.revoke();
            return JsUndefined.getInstance();
        }));
        return result;
    }
}
