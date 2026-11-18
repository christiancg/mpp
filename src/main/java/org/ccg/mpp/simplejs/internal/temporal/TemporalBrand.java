package org.ccg.mpp.simplejs.internal.temporal;

import org.ccg.mpp.simplejs.values.JsObject;
import org.ccg.mpp.simplejs.values.JsTemporalPlainDate;
import org.ccg.mpp.simplejs.values.JsTemporalPlainDateTime;
import org.ccg.mpp.simplejs.values.JsTemporalPlainMonthDay;
import org.ccg.mpp.simplejs.values.JsTemporalPlainTime;
import org.ccg.mpp.simplejs.values.JsTemporalPlainYearMonth;
import org.ccg.mpp.simplejs.values.JsTemporalZonedDateTime;
import org.ccg.mpp.simplejs.values.JsValue;

public final class TemporalBrand {
    public static boolean isTemporalWithCalendar(JsValue value) {
        return value instanceof JsTemporalPlainDate || value instanceof JsTemporalPlainDateTime
                || value instanceof JsTemporalPlainMonthDay || value instanceof JsTemporalPlainYearMonth
                || value instanceof JsTemporalZonedDateTime;
    }

    public static boolean isAnyTemporalValue(JsValue value) {
        return isTemporalWithCalendar(value) || value instanceof JsTemporalPlainTime;
    }

    public static boolean isTemporalLikeObject(JsValue value) {
        final var unwrapped = value instanceof JsObject wrapper ? wrapper.getPrimitive() : value;
        return unwrapped instanceof JsTemporalPlainDate || unwrapped instanceof JsTemporalPlainDateTime
                || unwrapped instanceof JsTemporalPlainMonthDay || unwrapped instanceof JsTemporalPlainTime
                || unwrapped instanceof JsTemporalPlainYearMonth || unwrapped instanceof JsTemporalZonedDateTime;
    }

    private TemporalBrand() {
    }
}
