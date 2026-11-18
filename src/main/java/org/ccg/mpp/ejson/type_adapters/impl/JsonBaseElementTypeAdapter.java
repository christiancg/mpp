package org.ccg.mpp.ejson.type_adapters.impl;

import org.ccg.mpp.ejson.elements.JsonBaseElement;
import org.ccg.mpp.ejson.internal.CharJsonOut;
import org.ccg.mpp.ejson.internal.JsonElementWriter;
import org.ccg.mpp.ejson.internal.JsonOut;
import org.ccg.mpp.ejson.type_adapters.TypeAdapter;

public class JsonBaseElementTypeAdapter implements TypeAdapter<JsonBaseElement> {
    @Override
    public String toJson(JsonBaseElement value) {
        final var out = new CharJsonOut();
        toJson(value, out);
        return out.toString();
    }

    @Override
    public void toJson(JsonBaseElement value, JsonOut out) {
        JsonElementWriter.write(value, out);
    }

    @Override
    public JsonBaseElement fromJson(JsonBaseElement value) {
        return value;
    }
}
