package org.ccg.mpp.unit.simplejs.host;

import java.util.function.Consumer;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.simplejs.host.HostBindings;
import org.ccg.mpp.simplejs.host.ModuleResolver;
import org.ccg.mpp.simplejs.host.ProjectAccess;
import org.ccg.mpp.simplejs.host.ResourceLimits;

public record ModuleHostBindings(JsonObject args, ProjectAccess project, Consumer<String> console,
        ResourceLimits limits, ModuleResolver moduleResolver) implements HostBindings {

    public static ModuleHostBindings of(ModuleResolver resolver, ResourceLimits limits) {
        return new ModuleHostBindings(new JsonObject(), null, null, limits, resolver);
    }

    public static ModuleHostBindings of(ModuleResolver resolver, ResourceLimits limits, ProjectAccess project) {
        return new ModuleHostBindings(new JsonObject(), project, null, limits, resolver);
    }
}
