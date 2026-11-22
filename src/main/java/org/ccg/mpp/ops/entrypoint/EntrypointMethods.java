package org.ccg.mpp.ops.entrypoint;

import java.util.Locale;
import java.util.Set;

public final class EntrypointMethods {
    public static final Set<String> ROUTABLE = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "QUERY");

    private EntrypointMethods() {
    }

    public static String normalized(String method) {
        if (method == null) {
            return null;
        }
        final var upper = method.trim().toUpperCase(Locale.ROOT);
        return ROUTABLE.contains(upper) ? upper : null;
    }
}
