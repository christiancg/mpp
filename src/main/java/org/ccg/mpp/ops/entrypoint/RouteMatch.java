package org.ccg.mpp.ops.entrypoint;

import java.util.Map;
import java.util.SortedSet;
import org.ccg.mpp.data.EntrypointDefinition;

public sealed interface RouteMatch {
    record Found(EntrypointDefinition definition, Map<String, String> pathParams) implements RouteMatch {
    }

    record MethodNotAllowed(SortedSet<String> allowed) implements RouteMatch {
    }

    record NoRoute() implements RouteMatch {
    }
}
