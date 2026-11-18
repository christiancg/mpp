package org.ccg.mpp.simplejs.builtins;

import static org.ccg.mpp.simplejs.values.JsObject.PropertyFlags.HIDDEN;
import static org.ccg.mpp.simplejs.values.JsObject.PropertyFlags.TAG;

import java.util.Iterator;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import org.ccg.mpp.simplejs.exceptions.TypeErrorException;
import org.ccg.mpp.simplejs.values.JsBoolean;
import org.ccg.mpp.simplejs.values.JsNativeFunction;
import org.ccg.mpp.simplejs.values.JsObject;
import org.ccg.mpp.simplejs.values.JsString;
import org.ccg.mpp.simplejs.values.JsSymbol;
import org.ccg.mpp.simplejs.values.JsUndefined;
import org.ccg.mpp.simplejs.values.JsValue;

public final class JsIterators {
    private interface Stepper extends Supplier<JsValue> {
    }

    private JsIterators() {
    }

    public static JsObject of(Iterator<JsValue> source) {
        return instance(() -> source.hasNext() ? source.next() : null);
    }

    public static JsObject lazy(IntFunction<JsValue> step) {
        final var cursor = new int[1];
        return instance(() -> {
            final var value = step.apply(cursor[0]);
            if (value == null) {
                return null;
            }
            cursor[0]++;
            return value;
        });
    }

    public static JsObject prototype(String tag, JsObject objectProto) {
        final var proto = new JsObject();
        proto.setProto(objectProto);
        proto.deferSetup(target -> {
            final var next = new JsNativeFunction("next", (thisArg, _) -> step(thisArg, tag, objectProto));
            next.setLength(0);
            target.defineValue("next", next);
            target.setFlags("next", HIDDEN);
            target.setSymbol(JsSymbol.TO_STRING_TAG, new JsString(tag));
            target.setSymbolFlags(JsSymbol.TO_STRING_TAG, TAG);
        });
        return proto;
    }

    public static JsValue linkPrototype(JsValue value, JsObject proto) {
        if (proto != null && value instanceof JsObject object && object.getInternalSlot() instanceof Stepper) {
            object.setProto(proto);
        }
        return value;
    }

    private static JsObject instance(Supplier<JsValue> stepper) {
        final var iterator = new JsObject();
        final var done = new boolean[]{false};
        iterator.setInternalSlot((Stepper) () -> {
            if (done[0]) {
                return null;
            }
            final var value = stepper.get();
            done[0] = value == null;
            return value;
        });
        return iterator;
    }

    private static JsValue step(JsValue receiver, String tag, JsObject objectProto) {
        if (!(receiver instanceof JsObject object) || !(object.getInternalSlot() instanceof Stepper stepper)) {
            throw new TypeErrorException(tag + ".next called on an incompatible receiver");
        }
        final var value = stepper.get();
        final var result = new JsObject();
        result.setProto(objectProto);
        result.set("value", value == null ? JsUndefined.getInstance() : value);
        result.set("done", JsBoolean.of(value == null));
        return result;
    }
}
