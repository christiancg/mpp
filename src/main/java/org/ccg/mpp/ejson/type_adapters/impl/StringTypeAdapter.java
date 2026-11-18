package org.ccg.mpp.ejson.type_adapters.impl;

import org.ccg.mpp.ejson.elements.JsonBaseElement;
import org.ccg.mpp.ejson.internal.CharJsonOut;
import org.ccg.mpp.ejson.internal.JsonOut;
import org.ccg.mpp.ejson.internal.JsonStrings;
import org.ccg.mpp.ejson.type_adapters.TypeAdapter;

public class StringTypeAdapter implements TypeAdapter<String> {

    @Override
    public String toJson(String value) {
        if (value == null) {
            return "null";
        }
        final var out = new CharJsonOut();
        toJson(value, out);
        return out.toString();
    }

    @Override
    public void toJson(String value, JsonOut out) {
        if (value == null) {
            out.append("null");
        } else {
            out.append('"');
            JsonStrings.appendEscaped(out, value);
            out.append('"');
        }
    }

    @Override
    public String fromJson(JsonBaseElement value) {
        if (value.isJsonString()) {
            return value.asJsonString().getValue();
        }
        return null;
    }
}
