package org.ccg.mpp.ops.middleware;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.MiddlewareCriteria;
import org.ccg.mpp.data.MiddlewareMatch;
import org.ccg.mpp.ops.entrypoint.EntrypointMethods;
import org.ccg.mpp.ops.req.MiddlewareCriteriaSpec;
import org.ccg.mpp.ops.req.MiddlewareMatchSpec;
import org.ccg.mpp.ops.req.validations.NameValidations;

public final class MiddlewareMatchParser {
    private MiddlewareMatchParser() {
    }

    public static MiddlewareMatch parse(MiddlewareMatchSpec spec, int maxEntrypoints)
            throws InvalidMiddlewareException {
        if (spec == null) {
            return MiddlewareMatch.EVERYTHING;
        }
        final var include = criteriaOf(spec, maxEntrypoints, "match");
        final var rules = spec.getExclude();
        if (rules == null) {
            return new MiddlewareMatch(include, List.of());
        }
        if (rules.size() > Globals.MIDDLEWARE_MAX_EXCLUDE_RULES) {
            throw new InvalidMiddlewareException(
                    "match.exclude holds more than " + Globals.MIDDLEWARE_MAX_EXCLUDE_RULES + " rules");
        }
        final var exclude = new ArrayList<MiddlewareCriteria>(rules.size());
        for (var i = 0; i < rules.size(); i++) {
            final var where = "match.exclude[" + i + "]";
            if (rules.get(i) == null) {
                throw new InvalidMiddlewareException(where + " must be an object");
            }
            final var rule = criteriaOf(rules.get(i), maxEntrypoints, where);
            if (rule.isUnconstrained()) {
                throw new InvalidMiddlewareException(
                        where + " needs at least one of 'methods', 'pathPrefix' and 'entrypoints'");
            }
            exclude.add(rule);
        }
        return new MiddlewareMatch(include, exclude);
    }

    private static MiddlewareCriteria criteriaOf(MiddlewareCriteriaSpec spec, int maxEntrypoints, String where)
            throws InvalidMiddlewareException {
        return new MiddlewareCriteria(methodsOf(spec.getMethods(), where), prefixOf(spec.getPathPrefix(), where),
                entrypointsOf(spec.getEntrypoints(), maxEntrypoints, where));
    }

    private static List<String> methodsOf(List<String> methods, String where) throws InvalidMiddlewareException {
        if (methods == null) {
            return List.of();
        }
        final var normalized = new LinkedHashSet<String>();
        for (final var method : methods) {
            final var upper = EntrypointMethods.normalized(method);
            if (upper == null) {
                throw new InvalidMiddlewareException(where + ".methods holds '" + method + "'; a method is one of "
                        + String.join(", ", EntrypointMethods.ROUTABLE.stream().sorted().toList()));
            }
            normalized.add(upper);
        }
        return List.copyOf(normalized);
    }

    private static String prefixOf(String prefix, String where) throws InvalidMiddlewareException {
        if (prefix == null) {
            return null;
        }
        return PathPrefix.normalized(prefix, where + ".pathPrefix");
    }

    private static List<String> entrypointsOf(List<String> names, int maxEntrypoints, String where)
            throws InvalidMiddlewareException {
        if (names == null) {
            return List.of();
        }
        if (names.size() > maxEntrypoints) {
            throw new InvalidMiddlewareException(where + ".entrypoints names more than " + maxEntrypoints);
        }
        final var distinct = new LinkedHashSet<String>();
        for (final var name : names) {
            if (!NameValidations.isValidProcedureName(name)) {
                throw new InvalidMiddlewareException(where + ".entrypoints holds '" + name
                        + "'; a name is 3-64 alphanumeric characters, underscores, or hyphens");
            }
            distinct.add(name);
        }
        return List.copyOf(distinct);
    }
}
