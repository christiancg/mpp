package org.ccg.mpp.simplejs.builtins;

import java.time.ZoneId;
import java.util.List;
import org.ccg.mpp.simplejs.builtins.temporal.ZonedDateTimeZones;
import org.ccg.mpp.simplejs.exceptions.TypeErrorException;
import org.ccg.mpp.simplejs.internal.temporal.TimeZoneStringParser;
import org.ccg.mpp.simplejs.values.JsNativeFunction;
import org.ccg.mpp.simplejs.values.JsObject;
import org.ccg.mpp.simplejs.values.JsString;
import org.ccg.mpp.simplejs.values.JsTemporalInstant;
import org.ccg.mpp.simplejs.values.JsTemporalPlainDate;
import org.ccg.mpp.simplejs.values.JsTemporalPlainDateTime;
import org.ccg.mpp.simplejs.values.JsTemporalPlainTime;
import org.ccg.mpp.simplejs.values.JsTemporalZonedDateTime;
import org.ccg.mpp.simplejs.values.JsUndefined;
import org.ccg.mpp.simplejs.values.JsValue;

public final class TemporalNowBuiltins {
    private record TimeZoneRef(ZoneId zone, String id) {
    }

    private TemporalNowBuiltins() {
    }

    public static void install(JsObject now, InterpreterOps ops) {
        Intrinsics.installMethod(now, "instant", new JsNativeFunction("instant", (_, _) -> instant()));
        Intrinsics.installMethod(now, "timeZoneId",
                new JsNativeFunction("timeZoneId", (_, _) -> new JsString(InterpreterOps.timeZone(ops).getId())));
        Intrinsics.installMethod(now, "plainDateISO", new JsNativeFunction("plainDateISO",
                (_, args) -> new JsTemporalPlainDate(fieldsAt(resolveTimeZone(args, ops)).date())));
        Intrinsics.installMethod(now, "plainTimeISO", new JsNativeFunction("plainTimeISO",
                (_, args) -> new JsTemporalPlainTime(fieldsAt(resolveTimeZone(args, ops)).time())));
        Intrinsics.installMethod(now, "plainDateTimeISO", new JsNativeFunction("plainDateTimeISO", (_, args) -> {
            final var fields = fieldsAt(resolveTimeZone(args, ops));
            return new JsTemporalPlainDateTime(fields.date(), fields.time());
        }));
        Intrinsics.installMethod(now, "zonedDateTimeISO",
                new JsNativeFunction("zonedDateTimeISO", (_, args) -> zonedDateTimeISO(resolveTimeZone(args, ops))));
    }

    private static JsTemporalInstant instant() {
        return JsTemporalInstant.fromEpochMilliseconds(System.currentTimeMillis());
    }

    private static JsTemporalInstant.IsoFieldsAt fieldsAt(TimeZoneRef ref) {
        final var current = instant();
        final var offset = ref.zone().getRules().getOffset(current.toJavaInstant());
        return current.isoFieldsAt(offset);
    }

    private static JsValue zonedDateTimeISO(TimeZoneRef ref) {
        final var current = instant();
        return new JsTemporalZonedDateTime(current.epochSecondsPart(), current.nanoAdjustment(), ref.zone(), ref.id());
    }

    private static TimeZoneRef resolveTimeZone(List<JsValue> args, InterpreterOps ops) {
        final var arg = args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
        if (arg instanceof JsUndefined) {
            final var zone = InterpreterOps.timeZone(ops);
            return new TimeZoneRef(zone, zone.getId());
        }
        final var zoned = asZonedDateTime(arg);
        if (zoned != null) {
            return new TimeZoneRef(zoned.zone(), zoned.timeZoneId());
        }
        if (!(arg instanceof JsString s)) {
            throw new TypeErrorException("timeZone must be a string");
        }
        final var id = TimeZoneStringParser.parseTimeZoneIdentifierFlexible(s.getValue());
        return new TimeZoneRef(ZonedDateTimeZones.zoneOf(id), id);
    }

    private static JsTemporalZonedDateTime asZonedDateTime(JsValue value) {
        if (value instanceof JsTemporalZonedDateTime zoned) {
            return zoned;
        }
        if (value instanceof JsObject wrapper && wrapper.getPrimitive() instanceof JsTemporalZonedDateTime zoned) {
            return zoned;
        }
        return null;
    }
}
