package org.ccg.mpp.simplejs.builtins.intrinsics;

import org.ccg.mpp.simplejs.builtins.DisposableStackBuiltins;
import org.ccg.mpp.simplejs.exceptions.TypeErrorException;
import org.ccg.mpp.simplejs.internal.JsCoercion;
import org.ccg.mpp.simplejs.values.JsArrayBuffer;
import org.ccg.mpp.simplejs.values.JsAsyncGenerator;
import org.ccg.mpp.simplejs.values.JsBigInt;
import org.ccg.mpp.simplejs.values.JsBoolean;
import org.ccg.mpp.simplejs.values.JsClass;
import org.ccg.mpp.simplejs.values.JsDataView;
import org.ccg.mpp.simplejs.values.JsDate;
import org.ccg.mpp.simplejs.values.JsDbDateTime;
import org.ccg.mpp.simplejs.values.JsDbTime;
import org.ccg.mpp.simplejs.values.JsFunction;
import org.ccg.mpp.simplejs.values.JsGenerator;
import org.ccg.mpp.simplejs.values.JsGeo;
import org.ccg.mpp.simplejs.values.JsMap;
import org.ccg.mpp.simplejs.values.JsNativeFunction;
import org.ccg.mpp.simplejs.values.JsNumber;
import org.ccg.mpp.simplejs.values.JsObject;
import org.ccg.mpp.simplejs.values.JsProxy;
import org.ccg.mpp.simplejs.values.JsRegExp;
import org.ccg.mpp.simplejs.values.JsSet;
import org.ccg.mpp.simplejs.values.JsSymbol;
import org.ccg.mpp.simplejs.values.JsTemporalDuration;
import org.ccg.mpp.simplejs.values.JsTemporalInstant;
import org.ccg.mpp.simplejs.values.JsTemporalPlainDate;
import org.ccg.mpp.simplejs.values.JsTemporalPlainDateTime;
import org.ccg.mpp.simplejs.values.JsTemporalPlainMonthDay;
import org.ccg.mpp.simplejs.values.JsTemporalPlainTime;
import org.ccg.mpp.simplejs.values.JsTemporalPlainYearMonth;
import org.ccg.mpp.simplejs.values.JsTemporalZonedDateTime;
import org.ccg.mpp.simplejs.values.JsTypedArray;
import org.ccg.mpp.simplejs.values.JsValue;
import org.ccg.mpp.simplejs.values.JsVector;

public final class IntrinsicsBrands {
    public static JsNumber requireNumber(JsValue receiver, String method) {
        return requireBrand(receiver, JsNumber.class, "Number.prototype", method);
    }

    public static JsBoolean requireBoolean(JsValue receiver, String method) {
        return requireBrand(receiver, JsBoolean.class, "Boolean.prototype", method);
    }

    public static JsBigInt requireBigInt(JsValue receiver, String method) {
        return requireBrand(receiver, JsBigInt.class, "BigInt.prototype", method);
    }

    public static JsSymbol requireSymbol(JsValue receiver, String method) {
        return requireBrand(receiver, JsSymbol.class, "Symbol.prototype", method);
    }

    public static JsRegExp requireRegExp(JsValue receiver, String method) {
        return requireBrand(receiver, JsRegExp.class, "RegExp.prototype", method);
    }

    public static JsGenerator requireGenerator(JsValue receiver, String method) {
        if (receiver instanceof JsGenerator generator) {
            return generator;
        }
        throw incompatible("Generator.prototype." + method, receiver);
    }

    public static JsAsyncGenerator requireAsyncGenerator(JsValue receiver, String method) {
        if (receiver instanceof JsAsyncGenerator generator) {
            return generator;
        }
        throw incompatible("AsyncGenerator.prototype." + method, receiver);
    }

    public static JsMap requireMap(JsValue receiver, String method, boolean weak) {
        if (receiver instanceof JsMap map && map.isWeak() == weak) {
            return map;
        }
        if (unwrap(receiver) instanceof JsMap wrapped && wrapped.isWeak() == weak) {
            return wrapped;
        }
        throw incompatible((weak ? "WeakMap.prototype." : "Map.prototype.") + method, receiver);
    }

    public static JsSet requireSet(JsValue receiver, String method, boolean weak) {
        if (receiver instanceof JsSet set && set.isWeak() == weak) {
            return set;
        }
        if (unwrap(receiver) instanceof JsSet wrapped && wrapped.isWeak() == weak) {
            return wrapped;
        }
        throw incompatible((weak ? "WeakSet.prototype." : "Set.prototype.") + method, receiver);
    }

    public static JsDate requireDate(JsValue receiver, String method) {
        return requireBrand(receiver, JsDate.class, "Date.prototype", method);
    }

    public static JsTemporalDuration requireTemporalDuration(JsValue receiver, String method) {
        return requireBrand(receiver, JsTemporalDuration.class, "Temporal.Duration.prototype", method);
    }

    public static JsTemporalPlainTime requireTemporalPlainTime(JsValue receiver, String method) {
        return requireBrand(receiver, JsTemporalPlainTime.class, "Temporal.PlainTime.prototype", method);
    }

    public static JsTemporalPlainDate requireTemporalPlainDate(JsValue receiver, String method) {
        return requireBrand(receiver, JsTemporalPlainDate.class, "Temporal.PlainDate.prototype", method);
    }

    public static JsTemporalInstant requireTemporalInstant(JsValue receiver, String method) {
        return requireBrand(receiver, JsTemporalInstant.class, "Temporal.Instant.prototype", method);
    }

    public static JsTemporalPlainYearMonth requireTemporalPlainYearMonth(JsValue receiver, String method) {
        return requireBrand(receiver, JsTemporalPlainYearMonth.class, "Temporal.PlainYearMonth.prototype", method);
    }

    public static JsTemporalPlainMonthDay requireTemporalPlainMonthDay(JsValue receiver, String method) {
        return requireBrand(receiver, JsTemporalPlainMonthDay.class, "Temporal.PlainMonthDay.prototype", method);
    }

    public static JsTemporalPlainDateTime requireTemporalPlainDateTime(JsValue receiver, String method) {
        return requireBrand(receiver, JsTemporalPlainDateTime.class, "Temporal.PlainDateTime.prototype", method);
    }

    public static JsTemporalZonedDateTime requireTemporalZonedDateTime(JsValue receiver, String method) {
        return requireBrand(receiver, JsTemporalZonedDateTime.class, "Temporal.ZonedDateTime.prototype", method);
    }

    public static JsGeo requireGeo(JsValue receiver, String method) {
        return requireBrand(receiver, JsGeo.class, "Geo.prototype", method);
    }

    public static JsVector requireVector(JsValue receiver, String method) {
        return requireBrand(receiver, JsVector.class, "Vector.prototype", method);
    }

    public static JsDbDateTime requireDbDateTime(JsValue receiver, String method) {
        return requireBrand(receiver, JsDbDateTime.class, "DbDateTime.prototype", method);
    }

    public static JsDbTime requireDbTime(JsValue receiver, String method) {
        return requireBrand(receiver, JsDbTime.class, "DbTime.prototype", method);
    }

    public static JsArrayBuffer requireBuffer(JsValue receiver, String method) {
        return requireBrand(receiver, JsArrayBuffer.class, "ArrayBuffer.prototype", method);
    }

    public static JsDataView requireView(JsValue receiver, String method) {
        return requireBrand(receiver, JsDataView.class, "DataView.prototype", method);
    }

    public static JsTypedArray requireTypedArray(JsValue receiver, String method) {
        return requireBrand(receiver, JsTypedArray.class, "TypedArray.prototype", method);
    }

    public static JsTypedArray requireUint8(JsValue receiver, String method) {
        final var typed = requireTypedArray(receiver, method);
        if (typed.kind() != JsTypedArray.Kind.UINT8) {
            throw incompatible("Uint8Array.prototype." + method, receiver);
        }
        return typed;
    }

    public static JsObject requireStack(JsValue receiver, String method) {
        if (receiver instanceof JsObject object && object.hasSymbol(DisposableStackBuiltins.entriesKey())) {
            return object;
        }
        throw incompatible("DisposableStack.prototype." + method, receiver);
    }

    public static JsObject requireObject(JsValue receiver) {
        if (receiver instanceof JsObject object) {
            return object;
        }
        throw incompatible("Object.prototype." + "toString", receiver);
    }

    public static JsValue requireCallable(JsValue receiver, String method) {
        if (receiver instanceof JsFunction || receiver instanceof JsNativeFunction || receiver instanceof JsClass
                || (receiver instanceof JsProxy proxy && proxy.isCallable())) {
            return receiver;
        }
        throw incompatible("Function" + ".prototype." + method, receiver);
    }

    public static <T extends JsValue> T requireBrand(JsValue receiver, Class<T> type, String label, String method) {
        if (type.isInstance(receiver)) {
            return type.cast(receiver);
        }
        if (type.isInstance(unwrap(receiver))) {
            return type.cast(unwrap(receiver));
        }
        throw incompatible(label + "." + method, receiver);
    }

    public static JsValue unwrap(JsValue receiver) {
        return receiver instanceof JsObject object ? object.getPrimitive() : null;
    }

    public static TypeErrorException incompatible(String method, JsValue receiver) {
        return new TypeErrorException(method + " called on an incompatible receiver " + JsCoercion.describe(receiver));
    }

    private IntrinsicsBrands() {
    }
}
