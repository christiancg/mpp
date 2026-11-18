package org.ccg.mpp.ejson.type_adapters.impl;

import org.ccg.mpp.ejson.elements.JsonBaseElement;
import org.ccg.mpp.ejson.elements.JsonCustom;
import org.ccg.mpp.ejson.internal.JsonElementWriter;
import org.ccg.mpp.ejson.internal.JsonOut;
import org.ccg.mpp.ejson.type_adapters.TypeAdapter;
import org.ccg.mpp.ejson.type_adapters.TypeAdapterFactory;

public class JsonCustomTypeAdapter implements TypeAdapter<JsonCustom<?>> {

    @Override
    public String toJson(JsonCustom<?> value) {
        return TypeAdapterFactory.getAdapter(JsonBaseElement.class).toJson(value);
    }

    @Override
    public void toJson(JsonCustom<?> value, JsonOut out) {
        JsonElementWriter.write(value, out);
    }

    @Override
    public JsonCustom<?> fromJson(JsonBaseElement value) {
        if (value instanceof JsonCustom<?>) {
            return (JsonCustom<?>) TypeAdapterFactory.getAdapter(JsonBaseElement.class).fromJson(value);
        } else {
            return null;
        }
    }
}
