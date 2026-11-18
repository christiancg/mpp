package org.ccg.mpp.simplejs.host;

import java.util.function.Consumer;
import org.ccg.mpp.ejson.elements.JsonObject;

public record SimpleHostBindings(JsonObject args, DatabaseAccess database, Consumer<String> console,
        ResourceLimits limits) implements HostBindings {
    public static SimpleHostBindings empty() {
        return new SimpleHostBindings(new JsonObject(), null, null, ResourceLimits.unlimited());
    }
}
