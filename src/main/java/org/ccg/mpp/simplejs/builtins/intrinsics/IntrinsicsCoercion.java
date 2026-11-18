package org.ccg.mpp.simplejs.builtins.intrinsics;

import java.util.List;
import org.ccg.mpp.simplejs.builtins.ArrayBuiltins;
import org.ccg.mpp.simplejs.builtins.BuiltinLengths;
import org.ccg.mpp.simplejs.builtins.Intrinsics;
import org.ccg.mpp.simplejs.builtins.Intrinsics.MethodResolver;
import org.ccg.mpp.simplejs.builtins.JsIterators;
import org.ccg.mpp.simplejs.exceptions.TypeErrorException;
import org.ccg.mpp.simplejs.internal.interpreter.InterpreterUtils;
import org.ccg.mpp.simplejs.values.JsNativeFunction;
import org.ccg.mpp.simplejs.values.JsNull;
import org.ccg.mpp.simplejs.values.JsObject;
import org.ccg.mpp.simplejs.values.JsUndefined;
import org.ccg.mpp.simplejs.values.JsValue;

public record IntrinsicsCoercion(Intrinsics intrinsics) {
    public JsValue callArrayMethod(JsValue thisArg, String name, List<JsValue> args) {
        if (thisArg instanceof JsNull || thisArg instanceof JsUndefined) {
            throw IntrinsicsBrands.incompatible("Array.prototype." + name, thisArg);
        }
        final var receiver = toObject(thisArg);
        final var resolved = ArrayBuiltins.getMethod(receiver, name, intrinsics.invoker, intrinsics.ops);
        if (resolved == null) {
            throw IntrinsicsBrands.incompatible("Array.prototype." + name, thisArg);
        }
        return JsIterators.linkPrototype(intrinsics.invoker.call(resolved, receiver, args),
                intrinsics.prototypes.builtinIteratorProto("Array.prototype", name));
    }

    public JsValue toObject(JsValue value) {
        if (InterpreterUtils.isObjectLike(value)) {
            return value;
        }
        if (value instanceof JsNull || value instanceof JsUndefined) {
            throw new TypeErrorException("Cannot convert undefined or null to object");
        }
        return wrapPrimitive(value, intrinsics.protoFor(value));
    }

    public JsObject wrapPrimitive(JsValue primitive, JsValue proto) {
        final var wrapper = new JsObject();
        wrapper.setProto(proto);
        wrapper.setPrimitive(primitive);
        return wrapper;
    }

    public JsObject prototypeOf(List<String> names, String label, MethodResolver resolver) {
        final var proto = new JsObject();
        proto.setProto(intrinsics.objectProto);
        proto.deferSetup(target -> installMethods(target, names, label, resolver));
        return proto;
    }

    public void installMethods(JsObject proto, List<String> names, String label, MethodResolver resolver) {
        final var lengths = BuiltinLengths.lengthsFor(label);
        for (final var name : names) {
            Intrinsics.define(proto, name, wrapper(name, label, resolver, BuiltinLengths.lengthIn(lengths, name)));
        }
    }

    public JsNativeFunction wrapper(String name, String label, MethodResolver resolver) {
        return wrapper(name, label, resolver, BuiltinLengths.lengthOf(label, name));
    }

    private JsNativeFunction wrapper(String name, String label, MethodResolver resolver, int length) {
        final var wrapped = new JsNativeFunction(name, (thisArg, args) -> {
            final var method = resolver.resolve(thisArg, name);
            if (method == null) {
                throw IntrinsicsBrands.incompatible(label + "." + name, thisArg);
            }
            return JsIterators.linkPrototype(intrinsics.invoker.call(method, thisArg, args),
                    intrinsics.prototypes.builtinIteratorProto(label, name));
        });
        wrapped.setLength(length);
        return wrapped;
    }
}
