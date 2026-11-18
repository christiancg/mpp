package org.ccg.mpp.simplejs.builtins;

import org.ccg.mpp.simplejs.exceptions.TypeErrorException;
import org.ccg.mpp.simplejs.values.JsObject;
import org.ccg.mpp.simplejs.values.JsString;
import org.ccg.mpp.simplejs.values.JsUndefined;
import org.ccg.mpp.simplejs.values.JsValue;

public final class NewTargetSupport {
    public static boolean isConstructCall(JsValue newTarget) {
        return newTarget != null && !(newTarget instanceof JsUndefined);
    }

    public static void requireNewTarget(String constructorName, JsValue thisArg, JsValue newTarget) {
        if (!isConstructCall(newTarget) && thisArg instanceof JsUndefined) {
            throw new TypeErrorException("Constructor " + constructorName + " requires 'new'");
        }
    }

    public static void requireNewTargetOrSubclassInstance(String constructorName, JsValue thisArg, JsValue newTarget) {
        if (isConstructCall(newTarget)) {
            return;
        }
        if (thisArg instanceof JsObject object && object.getKlass() != null) {
            return;
        }
        throw new TypeErrorException("Constructor " + constructorName + " requires 'new'");
    }

    public static JsValue withNewTargetPrototype(JsValue constructed, JsValue newTarget, InterpreterOps ops) {
        if (ops == null || !isConstructCall(newTarget)) {
            return constructed;
        }
        final var proto = ops.getMember(newTarget, new JsString("prototype"));
        if (!(proto instanceof JsObject requested) || proto == ops.getPrototypeOf(constructed)) {
            return constructed;
        }
        final var wrapper = new JsObject();
        wrapper.setPrimitive(constructed);
        wrapper.setProto(requested);
        return wrapper;
    }

    private NewTargetSupport() {
    }
}
