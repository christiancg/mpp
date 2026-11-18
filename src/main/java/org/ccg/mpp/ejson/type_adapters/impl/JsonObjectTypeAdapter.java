package org.ccg.mpp.ejson.type_adapters.impl;

import org.ccg.mpp.ejson.elements.JsonBaseElement;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.internal.CharJsonOut;
import org.ccg.mpp.ejson.internal.JsonElementWriter;
import org.ccg.mpp.ejson.internal.JsonOut;
import org.ccg.mpp.ejson.type_adapters.TypeAdapter;

public class JsonObjectTypeAdapter implements TypeAdapter<JsonObject> {

    @Override
    public String toJson(JsonObject value) {
        final var out = new CharJsonOut();
        toJson(value, out);
        return out.toString();
    }

    @Override
    public void toJson(JsonObject value, JsonOut out) {
        JsonElementWriter.write(value, out);
    }

    @Override
    public JsonObject fromJson(JsonBaseElement value) {
        return value.getJsonType() == JsonBaseElement.JsonType.OBJECT ? value.asJsonObject() : null;
    }
}
