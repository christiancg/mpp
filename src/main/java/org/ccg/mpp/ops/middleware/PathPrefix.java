package org.ccg.mpp.ops.middleware;

import org.ccg.mpp.ops.entrypoint.InvalidRouteException;
import org.ccg.mpp.ops.entrypoint.RouteTemplate;

public final class PathPrefix {
    private PathPrefix() {
    }

    public static String normalized(String prefix, String where) throws InvalidMiddlewareException {
        final var refusal = where + " is not valid: ";
        final RouteTemplate template;
        try {
            template = RouteTemplate.parse(prefix);
        } catch (InvalidRouteException e) {
            throw new InvalidMiddlewareException(refusal + e.getMessage());
        }
        for (final var segment : template.segments()) {
            if (segment instanceof RouteTemplate.Param(var name)) {
                throw new InvalidMiddlewareException(
                        refusal + "a path prefix holds literal segments only, not the parameter '{" + name + "}'");
            }
        }
        return template.normalized();
    }
}
