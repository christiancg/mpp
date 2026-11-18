package org.ccg.mpp.ejson.type_adapters.impl;

import org.ccg.mpp.ejson.elements.JsonArray;
import org.ccg.mpp.ejson.elements.JsonBaseElement;
import org.ccg.mpp.ejson.internal.CharJsonOut;
import org.ccg.mpp.ejson.internal.JsonElementWriter;
import org.ccg.mpp.ejson.internal.JsonOut;
import org.ccg.mpp.ejson.type_adapters.TypeAdapter;

public class JsonArrayTypeAdapter implements TypeAdapter<JsonArray> {

    @Override
    public String toJson(JsonArray value) {
        final var out = new CharJsonOut();
        toJson(value, out);
        return out.toString();
    }

    @Override
    public void toJson(JsonArray value, JsonOut out) {
        JsonElementWriter.write(value, out);
    }

    @Override
    public JsonArray fromJson(JsonBaseElement value) {
        return value == null
                ? null
                : value.getJsonType() == JsonBaseElement.JsonType.ARRAY ? value.asJsonArray() : null;
    }
}
