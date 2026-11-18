package org.ccg.mpp.simplejs.builtins.object;

import static org.ccg.mpp.simplejs.builtins.ObjectBuiltins.argAt;
import static org.ccg.mpp.simplejs.builtins.ObjectBuiltins.first;
import static org.ccg.mpp.simplejs.builtins.object.ObjectDescriptors.toPropertyKey;

import java.util.ArrayList;
import java.util.List;
import org.ccg.mpp.simplejs.builtins.InterpreterOps;
import org.ccg.mpp.simplejs.exceptions.TypeErrorException;
import org.ccg.mpp.simplejs.internal.JsCoercion;
import org.ccg.mpp.simplejs.internal.interpreter.InterpreterUtils;
import org.ccg.mpp.simplejs.values.JsArguments;
import org.ccg.mpp.simplejs.values.JsArray;
import org.ccg.mpp.simplejs.values.JsBoolean;
import org.ccg.mpp.simplejs.values.JsCallableProperties;
import org.ccg.mpp.simplejs.values.JsClass;
import org.ccg.mpp.simplejs.values.JsGlobalObject;
import org.ccg.mpp.simplejs.values.JsObject;
import org.ccg.mpp.simplejs.values.JsProxy;
import org.ccg.mpp.simplejs.values.JsString;
import org.ccg.mpp.simplejs.values.JsSymbol;
import org.ccg.mpp.simplejs.values.JsTypedArray;
import org.ccg.mpp.simplejs.values.JsUndefined;
import org.ccg.mpp.simplejs.values.JsValue;
import org.ccg.mpp.simplejs.values.OrdinaryProperties;
import org.ccg.mpp.simplejs.values.PropertyTable;

public final class ObjectOwnKeys {
    public static JsValue getOwnPropertySymbols(List<JsValue> args, InterpreterOps ops) {
        final var result = new JsArray();
        for (final var key : ops.ownKeys(first(args))) {
            if (key instanceof JsSymbol) {
                result.push(key);
            }
        }
        return result;
    }

    public static JsValue hasOwn(List<JsValue> args, InterpreterOps ops) {
        if (args.isEmpty() || InterpreterUtils.isNullish(args.getFirst())) {
            throw new TypeErrorException("Cannot convert undefined or null to object");
        }
        final var key = toPropertyKey(argAt(args, 1), ops);
        if (key instanceof JsSymbol symbol) {
            return JsBoolean.of(hasOwnSymbol(args.getFirst(), symbol, ops));
        }
        return JsBoolean.of(hasOwnKey(args.getFirst(), JsCoercion.toStr(key, ops), ops));
    }

    public static boolean hasOwnKey(JsValue target, String key, InterpreterOps ops) {
        return switch (target) {
            case JsProxy proxy ->
                ops != null && !(ops.getOwnPropertyDescriptor(proxy, new JsString(key)) instanceof JsUndefined);
            case JsObject object -> object.hasOwnKey(new JsString(key)) || object.hasAccessor(key)
                    || (object.getPrimitive() != null && hasOwnKey(object.getPrimitive(), key, ops));
            case JsClass cls -> cls.getStaticOwner().has(key) || cls.getStaticOwner().hasAccessor(key);
            case JsArray array -> "length".equals(key) || arrayHasIndex(array, key);
            case JsString string -> "length".equals(key) || stringHasIndex(string, key);
            case JsTypedArray typed ->
                "length".equals(key) || typedHasIndex(typed, key) || hasTableAccessor(target, key)
                        || (target.ownProperties() != null && target.ownProperties().has(key));
            case JsGlobalObject global ->
                global.getEnv().hasGlobalProperty(key) || global.ownProperties().hasAccessor(key);
            case JsArguments arguments -> arguments.hasOwnKey(new JsString(key));
            case JsCallableProperties callable -> callable.hasProperty(key)
                    || OrdinaryProperties.metadataKey(callable, key) || hasTableAccessor(target, key);
            default -> target.hasOwnKey(new JsString(key));
        };
    }

    public static boolean hasTableAccessor(JsValue target, String key) {
        final var table = target.ownProperties();
        return table != null && table.hasAccessor(key);
    }

    public static boolean hasOwnSymbol(JsValue target, JsSymbol key, InterpreterOps ops) {
        if (target instanceof JsProxy proxy) {
            return ops != null && !(ops.getOwnPropertyDescriptor(proxy, key) instanceof JsUndefined);
        }
        final var table = symbolTableOf(target);
        return table != null && (table.hasSymbol(key) || table.hasSymbolAccessor(key));
    }

    public static boolean isEnumerableOwnSymbol(JsValue target, JsSymbol key) {
        final var table = symbolTableOf(target);
        return table != null && table.getSymbolFlags(key).enumerable();
    }

    public static PropertyTable symbolTableOf(JsValue target) {
        return target instanceof JsClass cls ? cls.getStaticOwner().ownProperties() : target.ownProperties();
    }

    public static boolean arrayHasIndex(JsArray array, String key) {
        final var index = InterpreterUtils.arrayIndex(key);
        if (index != null) {
            return (index < array.length() && !array.isHole(index)) || array.hasIndexAccessor(index);
        }
        return array.hasProperty(key) || array.hasPropAccessor(key);
    }

    public static boolean stringHasIndex(JsString string, String key) {
        final var index = InterpreterUtils.arrayIndex(key);
        return index != null && index < string.getValue().length();
    }

    public static boolean typedHasIndex(JsTypedArray typed, String key) {
        final var index = InterpreterUtils.arrayIndex(key);
        return index != null && index < typed.length();
    }

    public static JsValue setIntegrityLevel(List<JsValue> args, InterpreterOps ops, boolean frozen) {
        final var target = first(args);
        if (!InterpreterUtils.isObjectLike(target)) {
            return target;
        }
        if (!ops.preventExtensions(target)) {
            throw new TypeErrorException("Cannot prevent extensions");
        }
        for (final var key : ops.ownKeys(target)) {
            final var current = ops.getOwnPropertyDescriptor(target, key);
            if (!(current instanceof JsObject descriptor)) {
                continue;
            }
            if (!ops.defineProperty(target, key, integrityDescriptor(descriptor, frozen))) {
                throw new TypeErrorException("Cannot redefine property: " + JsCoercion.describe(key));
            }
        }
        return target;
    }

    public static JsValue integrityDescriptor(JsObject current, boolean frozen) {
        final var descriptor = new JsObject();
        descriptor.set("configurable", JsBoolean.FALSE);
        if (frozen && !current.has("get") && !current.has("set")) {
            descriptor.set("writable", JsBoolean.FALSE);
        }
        return descriptor;
    }

    public static JsValue testIntegrityLevel(List<JsValue> args, InterpreterOps ops, boolean frozen) {
        final var target = first(args);
        if (!InterpreterUtils.isObjectLike(target)) {
            return JsBoolean.TRUE;
        }
        if (ops.isExtensible(target)) {
            return JsBoolean.FALSE;
        }
        for (final var key : ops.ownKeys(target)) {
            if (!(ops.getOwnPropertyDescriptor(target, key) instanceof JsObject descriptor)) {
                continue;
            }
            if (JsCoercion.toBoolean(descriptor.get("configurable"))) {
                return JsBoolean.FALSE;
            }
            if (frozen && descriptor.has("writable") && JsCoercion.toBoolean(descriptor.get("writable"))) {
                return JsBoolean.FALSE;
            }
        }
        return JsBoolean.TRUE;
    }

    public static JsValue preventExtensions(List<JsValue> args) {
        final var target = first(args);
        switch (target) {
            case JsObject object -> object.preventExtensions();
            case JsArray array -> array.preventExtensions();
            default -> {
                final var table = target.ownProperties();
                if (table != null) {
                    table.preventExtensions();
                }
            }
        }
        return target;
    }

    public static JsValue isExtensible(List<JsValue> args) {
        return JsBoolean.of(first(args).isExtensible());
    }

    public static List<String> enumerableProxyStringKeys(JsValue proxy, InterpreterOps ops) {
        final var keys = new ArrayList<String>();
        for (final var key : ops.ownKeys(proxy)) {
            if (!(key instanceof JsString string)) {
                continue;
            }
            final var descriptor = ops.getOwnPropertyDescriptor(proxy, key);
            if (descriptor instanceof JsObject desc && desc.has("enumerable")
                    && JsCoercion.toBoolean(desc.get("enumerable"))) {
                keys.add(string.getValue());
            }
        }
        return keys;
    }

    public static JsValue getOwnPropertyNames(List<JsValue> args, InterpreterOps ops) {
        final var result = new JsArray();
        if (first(args) instanceof JsProxy proxy) {
            for (final var key : ops.ownKeys(proxy)) {
                result.push(key);
            }
            return result;
        }
        for (final var key : first(args).ownPropertyKeys()) {
            if (key instanceof JsString) {
                result.push(key);
            }
        }
        return result;
    }

    private ObjectOwnKeys() {
    }
}
