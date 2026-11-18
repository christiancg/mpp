package org.ccg.mpp.simplejs.builtins;

import static org.ccg.mpp.simplejs.builtins.NewTargetSupport.requireNewTarget;
import static org.ccg.mpp.simplejs.builtins.NewTargetSupport.withNewTargetPrototype;

import java.util.List;
import org.ccg.mpp.ejson.custom_types.JsonVector;
import org.ccg.mpp.simplejs.exceptions.RangeErrorException;
import org.ccg.mpp.simplejs.exceptions.TypeErrorException;
import org.ccg.mpp.simplejs.internal.JsCoercion;
import org.ccg.mpp.simplejs.internal.interpreter.InterpreterUtils;
import org.ccg.mpp.simplejs.values.JsArray;
import org.ccg.mpp.simplejs.values.JsNativeFunction;
import org.ccg.mpp.simplejs.values.JsNumber;
import org.ccg.mpp.simplejs.values.JsObject;
import org.ccg.mpp.simplejs.values.JsString;
import org.ccg.mpp.simplejs.values.JsUndefined;
import org.ccg.mpp.simplejs.values.JsValue;
import org.ccg.mpp.simplejs.values.JsVector;
import org.ccg.mpp.utils.VectorUtils;

public final class VectorBuiltins {
    public static final List<String> NAMES = List.of("at", "toArray", "toString", "toJSON");
    public static final List<String> FIELD_ACCESSORS = List.of("length", "simHash");

    private VectorBuiltins() {
    }

    public static JsNativeFunction create(InterpreterOps ops) {
        final var ctor = new JsNativeFunction("Vector", (thisArg, args, newTarget) -> {
            requireNewTarget("Vector", thisArg, newTarget);
            return withNewTargetPrototype(new JsVector(construct(args, ops)), newTarget, ops);
        });
        final var from = new JsNativeFunction("from", (_, args) -> new JsVector(toComponents(firstArg(args), ops)));
        from.setLength(1);
        ctor.setProperty("from", from);
        return ctor;
    }

    private static double[] construct(List<JsValue> args, InterpreterOps ops) {
        if (args.size() == 1 && InterpreterUtils.isObjectLike(args.getFirst())) {
            return toComponents(args.getFirst(), ops);
        }
        final var components = new double[args.size()];
        for (var i = 0; i < args.size(); i++) {
            components[i] = JsCoercion.toNumber(args.get(i), ops);
        }
        return requireNonEmpty(components);
    }

    private static double[] toComponents(JsValue value, InterpreterOps ops) {
        if (value instanceof JsVector vector) {
            return vector.getComponents();
        }
        if (value instanceof JsObject wrapper && wrapper.getPrimitive() instanceof JsVector wrapped) {
            return wrapped.getComponents();
        }
        if (value instanceof JsString text) {
            return requireNonEmpty(parse(text.getValue()));
        }
        if (value instanceof JsArray array) {
            final var elements = array.getElements();
            InterpreterOps.chargeArrayLike(ops, elements.size());
            final var components = new double[elements.size()];
            for (var i = 0; i < components.length; i++) {
                components[i] = JsCoercion.toNumber(elements.get(i), ops);
            }
            return requireNonEmpty(components);
        }
        if (InterpreterUtils.isObjectLike(value)) {
            return requireNonEmpty(fromArrayLike(value, ops));
        }
        throw new TypeErrorException(
                "Cannot convert to Vector: expected a Vector, a '#vector(...)' string or an array of numbers");
    }

    private static double[] fromArrayLike(JsValue value, InterpreterOps ops) {
        final var length = JsCoercion.toNumber(ops.getMember(value, new JsString("length")), ops);
        final var count = Double.isNaN(length) || length <= 0 ? 0L : (long) Math.min(length, Integer.MAX_VALUE);
        InterpreterOps.chargeArrayLike(ops, count);
        final var result = new double[(int) count];
        for (var i = 0; i < result.length; i++) {
            InterpreterOps.pace(ops, i);
            result[i] = JsCoercion.toNumber(ops.getMember(value, new JsString(Integer.toString(i))), ops);
        }
        return result;
    }

    private static double[] parse(String text) {
        try {
            return new JsonVector(text).getCustomValue();
        } catch (RuntimeException e) {
            throw new RangeErrorException("Invalid vector string: '" + text + "'");
        }
    }

    private static double[] requireNonEmpty(double[] components) {
        if (components.length == 0) {
            throw new RangeErrorException("A Vector must have at least one component");
        }
        return components;
    }

    private static JsValue firstArg(List<JsValue> args) {
        return args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
    }

    public static void installAccessors(JsObject proto) {
        for (final var name : FIELD_ACCESSORS) {
            final var getter = new JsNativeFunction("get " + name,
                    (thisArg, _) -> fieldAccessor(requireReceiver(thisArg, name), name));
            getter.setLength(0);
            proto.defineAccessor(name, getter, null);
            proto.setFlags(name, JsObject.PropertyFlags.HIDDEN);
        }
    }

    private static JsVector requireReceiver(JsValue receiver, String method) {
        if (receiver instanceof JsVector vector) {
            return vector;
        }
        if (receiver instanceof JsObject wrapper && wrapper.getPrimitive() instanceof JsVector wrapped) {
            return wrapped;
        }
        throw new TypeErrorException("Vector.prototype." + method + " called on an incompatible receiver");
    }

    public static JsValue fieldAccessor(JsVector receiver, String name) {
        return switch (name) {
            case "length" -> new JsNumber(receiver.length());
            case "simHash" -> new JsString(simHash(receiver));
            default -> null;
        };
    }

    private static String simHash(JsVector receiver) {
        return VectorUtils.simHash(receiver.getComponents(), JsonVector.SIMHASH_BITS);
    }

    public static JsValue getMethod(JsVector receiver, String name, InterpreterOps ops) {
        return switch (name) {
            case "at" -> new JsNativeFunction("at", (_, args) -> at(receiver, firstArg(args), ops));
            case "toArray" -> new JsNativeFunction("toArray", (_, _) -> toArray(receiver));
            case "toString" -> new JsNativeFunction("toString", (_, _) -> new JsString(receiver.toString()));
            case "toJSON" -> new JsNativeFunction("toJSON", (_, _) -> new JsString(receiver.toString()));
            default -> null;
        };
    }

    private static JsValue at(JsVector receiver, JsValue indexArg, InterpreterOps ops) {
        final var relative = (long) JsCoercion.toNumber(indexArg, ops);
        final var index = relative < 0 ? receiver.length() + relative : relative;
        if (index < 0 || index >= receiver.length()) {
            return JsUndefined.getInstance();
        }
        return new JsNumber(receiver.at((int) index));
    }

    private static JsValue toArray(JsVector receiver) {
        final var array = new JsArray();
        for (final var component : receiver.getComponents()) {
            array.push(new JsNumber(component));
        }
        return array;
    }
}
