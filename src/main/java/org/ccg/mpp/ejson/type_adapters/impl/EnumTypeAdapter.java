package org.ccg.mpp.ejson.type_adapters.impl;

import org.ccg.mpp.ejson.elements.JsonBaseElement;
import org.ccg.mpp.ejson.internal.EnumWireNames;
import org.ccg.mpp.ejson.internal.JsonOut;
import org.ccg.mpp.ejson.type_adapters.TypeAdapter;
import org.ccg.mpp.log.Logger;

public class EnumTypeAdapter<T extends Enum<T>> implements TypeAdapter<T> {

    private static final int MAX_LOGGED_VALUE_CHARS = 64;
    private final Logger logger = Logger.logFor(EnumTypeAdapter.class);

    private final Class<T> clazz;

    public EnumTypeAdapter(Class<T> clazz) {
        this.clazz = clazz;
    }

    @Override
    public String toJson(T value) {
        return "\"" + nameOf(value) + "\"";
    }

    @Override
    public void toJson(T value, JsonOut out) {
        out.append('"').append(nameOf(value)).append('"');
    }

    private String nameOf(T value) {
        return value == null ? String.valueOf((Object) null) : EnumWireNames.of(value);
    }

    @Override
    public T fromJson(JsonBaseElement value) {
        if (value.isJsonString()) {
            final var name = value.asJsonString().getValue();
            final var constant = clazz.cast(EnumWireNames.constantOf(clazz, name));
            if (constant == null) {
                logger.warning("Unknown enum value for " + clazz.getSimpleName() + ": " + truncated(name));
            }
            return constant;
        }
        return null;
    }

    private static String truncated(String name) {
        return name.length() > MAX_LOGGED_VALUE_CHARS ? name.substring(0, MAX_LOGGED_VALUE_CHARS) : name;
    }
}
