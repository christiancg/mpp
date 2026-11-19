package org.ccg.mpp.unit.simplejs.host;

import java.util.function.Consumer;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.simplejs.host.HostBindings;
import org.ccg.mpp.simplejs.host.NetworkAccess;
import org.ccg.mpp.simplejs.host.ProjectAccess;
import org.ccg.mpp.simplejs.host.ResourceLimits;

public record NetworkHostBindings(JsonObject args, ProjectAccess project, Consumer<String> console,
        ResourceLimits limits, NetworkAccess network) implements HostBindings {

    public static NetworkHostBindings of(NetworkAccess network, ResourceLimits limits) {
        return new NetworkHostBindings(new JsonObject(), null, null, limits, network);
    }
}
