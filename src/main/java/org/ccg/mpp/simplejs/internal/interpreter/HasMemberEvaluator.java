package org.ccg.mpp.simplejs.internal.interpreter;

import static org.ccg.mpp.simplejs.internal.interpreter.InterpreterUtils.*;

import org.ccg.mpp.simplejs.builtins.FunctionProtoBuiltins;
import org.ccg.mpp.simplejs.exceptions.TypeErrorException;
import org.ccg.mpp.simplejs.internal.Interpreter;
import org.ccg.mpp.simplejs.internal.JsCoercion;
import org.ccg.mpp.simplejs.values.JsArguments;
import org.ccg.mpp.simplejs.values.JsArray;
import org.ccg.mpp.simplejs.values.JsCallableProperties;
import org.ccg.mpp.simplejs.values.JsClass;
import org.ccg.mpp.simplejs.values.JsFunction;
import org.ccg.mpp.simplejs.values.JsGlobalObject;
import org.ccg.mpp.simplejs.values.JsObject;
import org.ccg.mpp.simplejs.values.JsProxy;
import org.ccg.mpp.simplejs.values.JsSymbol;
import org.ccg.mpp.simplejs.values.JsTypedArray;
import org.ccg.mpp.simplejs.values.JsValue;

public record HasMemberEvaluator(Interpreter interp) {
    public boolean hasMember(JsValue container, JsValue rawKey) {
        if (!isObjectLike(container)) {
            throw new TypeErrorException(
                    "Cannot use 'in' operator to search for '" + JsCoercion.describe(rawKey) + "'");
        }
        final var keyValue = JsCoercion.toPropertyKey(rawKey, interp.ops);
        return switch (container) {
            case JsProxy proxy -> interp.proxies.has(proxy, keyValue);
            case JsGlobalObject global -> global.getEnv().isDeclared(JsCoercion.toStr(keyValue, interp.ops));
            case JsObject object when keyValue instanceof JsSymbol symbol -> hasSymbolMember(object, symbol);
            case JsObject object -> hasStringMember(object, JsCoercion.toStr(keyValue, interp.ops));
            case JsClass cls when keyValue instanceof JsSymbol symbol -> hasStaticSymbolMember(cls, symbol);
            case JsClass cls -> hasStaticMember(cls, JsCoercion.toStr(keyValue, interp.ops));
            case JsArray array when keyValue instanceof JsSymbol -> exoticHasMember(array, keyValue);
            case JsArray array ->
                arrayHasMember(array, JsCoercion.toStr(keyValue, interp.ops)) || exoticHasMember(array, keyValue);
            case JsArguments arguments -> exoticHasMember(arguments, keyValue);
            case JsTypedArray typed when typed.hasCanonicalNumericIndex(keyValue) -> typed.hasOwnKey(keyValue);
            case JsTypedArray typed -> indexInRange(keyValue, typed.length()) || exoticHasMember(typed, keyValue);
            case JsCallableProperties callable when keyValue instanceof JsSymbol ->
                exoticHasMember((JsValue) callable, keyValue);
            case JsCallableProperties callable -> callableHasMember(callable, JsCoercion.toStr(keyValue, interp.ops));
            default -> exoticHasMember(container, keyValue);
        };
    }

    public boolean indexInRange(JsValue keyValue, int length) {
        if (keyValue instanceof JsSymbol) {
            return false;
        }
        final var key = JsCoercion.toStr(keyValue, interp.ops);
        final var index = arrayIndex(key);
        return index != null && index < length;
    }

    public boolean exoticHasMember(JsValue container, JsValue keyValue) {
        final var table = container.ownProperties();
        if (table != null) {
            if (keyValue instanceof JsSymbol symbol && (table.hasSymbol(symbol) || table.hasSymbolAccessor(symbol))) {
                return true;
            }
            if (!(keyValue instanceof JsSymbol)) {
                final var key = JsCoercion.toStr(keyValue, interp.ops);
                if (table.has(key) || table.hasAccessor(key)) {
                    return true;
                }
            }
        }
        final var chainStart = container.getProto() == null
                ? interp.intrinsics.protoFor(container)
                : container.getProto();
        if (keyValue instanceof JsSymbol symbol) {
            return interp.members.chainHasSymbol(chainStart, symbol);
        }
        return interp.members.chainHasKey(chainStart, JsCoercion.toStr(keyValue, interp.ops));
    }

    public boolean callableHasMember(JsCallableProperties callable, String key) {
        if (callable.hasProperty(key) || FunctionProtoBuiltins.metadata((JsValue) callable, key) != null) {
            return true;
        }
        if (callable instanceof JsFunction function && "prototype".equals(key) && !function.isArrow()
                && !function.isMethod() && !(function.isAsync() && !function.isGenerator())) {
            return true;
        }
        return interp.members.chainHasKey(interp.intrinsics.protoFor((JsValue) callable), key);
    }

    public boolean hasStaticMember(JsClass cls, String key) {
        if ("prototype".equals(key) || "name".equals(key)) {
            return true;
        }
        if (cls.findStaticGetter(key) != null || cls.findStaticSetter(key) != null
                || cls.findStaticMethod(key) != null) {
            return true;
        }
        for (var current = cls; current != null; current = current.getSuperClass()) {
            if (current.hasStaticProp(key)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasStaticSymbolMember(JsClass cls, JsSymbol symbol) {
        if (cls.findStaticSymbolGetter(symbol) != null || cls.findStaticSymbolSetter(symbol) != null
                || cls.findStaticSymbolMethod(symbol) != null) {
            return true;
        }
        for (var current = cls; current != null; current = current.getSuperClass()) {
            if (current.hasStaticSymbolProp(symbol)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasStringMember(JsObject object, String key) {
        return interp.members.chainHasKey(object, key);
    }

    public boolean hasSymbolMember(JsObject object, JsSymbol symbol) {
        return interp.members.chainHasSymbol(object, symbol);
    }

}
