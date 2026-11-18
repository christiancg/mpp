package org.ccg.mpp.ejson.type_adapters;

import java.util.Set;
import org.ccg.mpp.ejson.elements.JsonBaseElement;
import org.ccg.mpp.ejson.internal.JsonOut;

public interface TypeAdapter<T> {
    String toJson(T value);
    T fromJson(JsonBaseElement value);

    default T fromJson(JsonBaseElement value, Set<String> skippedKeys) {
        return fromJson(value);
    }

    default void toJson(T value, JsonOut out) {
        out.append(toJson(value));
    }
}
