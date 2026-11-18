package org.ccg.mpp.ejson.internal;

import org.ccg.mpp.ejson.elements.JsonBaseElement;
import org.ccg.mpp.ejson.type_adapters.TypeAdapterFactory;

public final class Assigner {
    private Assigner() {
    }
    public static <T> T assign(JsonBaseElement parsed, Class<T> tClass) {
        final var adapter = TypeAdapterFactory.getAdapter(tClass);
        return adapter.fromJson(parsed);
    }
}
