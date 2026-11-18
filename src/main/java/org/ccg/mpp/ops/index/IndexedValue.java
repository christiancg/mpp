package org.ccg.mpp.ops.index;

import org.ccg.mpp.data.FieldIndexEntry;
import org.ccg.mpp.data.IndexKind;
import org.ccg.mpp.ejson.custom_types.CustomTypeFactory;
import org.ccg.mpp.ejson.custom_types.JsonVector;
import org.ccg.mpp.ejson.elements.JsonBaseElement;
import org.ccg.mpp.ejson.elements.JsonBoolean;
import org.ccg.mpp.ejson.elements.JsonNull;
import org.ccg.mpp.ejson.elements.JsonNumber;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.utils.JsonUtils;

public record IndexedValue(Class<?> type, IndexKind kind, String key, Object value) {

    public static IndexedValue of(JsonBaseElement element) {
        if (element == null || element == JsonNull.INSTANCE) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            final var primitive = element.asJsonPrimitive();
            return switch (primitive) {
                case JsonNumber number -> scalar(Number.class, number.doubleValue() + 0.0);
                case JsonString string when string.isJsonCustom() -> {
                    final var custom = CustomTypeFactory.getCustomTypeInstance(string);
                    yield scalar(custom.getClass(), custom instanceof JsonVector vector ? vector.asIndexKey() : custom);
                }
                case JsonString string -> scalar(String.class, string.getValue());
                case JsonBoolean bool -> scalar(Boolean.class, bool.getValue());
                default -> throw new IllegalStateException("Unexpected value: " + primitive);
            };
        }
        if (element.isJsonObject()) {
            return hashed(IndexKind.OBJECT, JsonUtils.hashElement(element));
        }
        if (element.isJsonArray()) {
            return hashed(IndexKind.ARRAY, JsonUtils.hashElement(element));
        }
        return null;
    }

    public static boolean holdsSameScalar(Object held, JsonBaseElement element) {
        return switch (element) {
            case JsonNumber number -> held instanceof Double value
                    && Double.doubleToLongBits(value) == Double.doubleToLongBits(number.doubleValue() + 0.0);
            case JsonString string when string.isJsonCustom() -> false;
            case JsonString string -> held instanceof String value && value.equals(string.getValue());
            case JsonBoolean bool -> held instanceof Boolean value && value.equals(bool.getValue());
            case null, default -> false;
        };
    }

    private static IndexedValue scalar(Class<?> type, Object value) {
        return new IndexedValue(type, null, FieldIndexEntry.fileKeyOf(value), value);
    }

    private static IndexedValue hashed(IndexKind kind, String hash) {
        return new IndexedValue(null, kind, FieldIndexEntry.fileKeyOf(hash), hash);
    }

    public boolean isHashed() {
        return kind != null;
    }
}
