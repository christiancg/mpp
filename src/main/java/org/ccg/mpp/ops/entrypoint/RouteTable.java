package org.ccg.mpp.ops.entrypoint;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import org.ccg.mpp.data.EntrypointDefinition;

public final class RouteTable {
    public static final RouteTable EMPTY = new RouteTable(Map.of(), Map.of());
    private static final RouteMatch NO_ROUTE = new RouteMatch.NoRoute();
    private static final Comparator<Route> MOST_SPECIFIC_FIRST = Comparator
            .comparing(Route::template, RouteTemplate::compareSpecificity)
            .thenComparing(route -> route.definition().getName());

    private record Route(RouteTemplate template, EntrypointDefinition definition) {
    }

    private final Map<Integer, Map<String, List<Route>>> bySegmentCount;
    private final Map<String, EntrypointDefinition> byName;

    private RouteTable(Map<Integer, Map<String, List<Route>>> bySegmentCount,
            Map<String, EntrypointDefinition> byName) {
        this.bySegmentCount = bySegmentCount;
        this.byName = byName;
    }

    public static RouteTable of(Collection<EntrypointDefinition> definitions,
            BiConsumer<EntrypointDefinition, String> unroutable) {
        final var grouped = new HashMap<Integer, Map<String, List<Route>>>();
        final var byName = new HashMap<String, EntrypointDefinition>();
        for (final var definition : definitions) {
            byName.put(definition.getName(), definition);
            if (!definition.isEnabled()) {
                continue;
            }
            final var method = EntrypointMethods.normalized(definition.getMethod());
            if (method == null) {
                unroutable.accept(definition, "its method '" + definition.getMethod() + "' cannot be routed");
                continue;
            }
            final RouteTemplate template;
            try {
                template = RouteTemplate.parse(definition.getPath());
            } catch (InvalidRouteException e) {
                unroutable.accept(definition, e.getMessage());
                continue;
            }
            grouped.computeIfAbsent(template.segments().size(), ignored -> new HashMap<>())
                    .computeIfAbsent(method, ignored -> new ArrayList<>()).add(new Route(template, definition));
        }
        final var frozen = new HashMap<Integer, Map<String, List<Route>>>();
        grouped.forEach((count, byMethod) -> {
            final var sorted = new HashMap<String, List<Route>>();
            byMethod.forEach((method, routes) -> {
                routes.sort(MOST_SPECIFIC_FIRST);
                sorted.put(method, List.copyOf(routes));
            });
            frozen.put(count, Map.copyOf(sorted));
        });
        return new RouteTable(Map.copyOf(frozen), Map.copyOf(byName));
    }

    public RouteMatch match(String method, List<String> segments) {
        final var byMethod = bySegmentCount.get(segments.size());
        if (byMethod == null) {
            return NO_ROUTE;
        }
        final var found = firstMatch(byMethod.get(method), segments);
        if (found != null) {
            return found;
        }
        final var allowed = new TreeSet<String>();
        byMethod.forEach((other, routes) -> {
            if (firstMatch(routes, segments) != null) {
                allowed.add(other);
            }
        });
        return allowed.isEmpty() ? NO_ROUTE : new RouteMatch.MethodNotAllowed(allowed);
    }

    private static RouteMatch.Found firstMatch(List<Route> routes, List<String> segments) {
        if (routes == null) {
            return null;
        }
        for (final var route : routes) {
            final var params = route.template().match(segments);
            if (params != null) {
                return new RouteMatch.Found(route.definition(), params);
            }
        }
        return null;
    }

    public EntrypointDefinition definition(String name) {
        return byName.get(name);
    }
}
