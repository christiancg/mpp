package org.ccg.mpp.unit.ops.entrypoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.ccg.mpp.data.EntrypointDefinition;
import org.ccg.mpp.ops.entrypoint.RouteMatch;
import org.ccg.mpp.ops.entrypoint.RouteTable;
import org.junit.jupiter.api.Test;

public class RouteTableTest {
    private final List<String> unroutable = new ArrayList<>();

    private static EntrypointDefinition entrypoint(String name, String method, String path, boolean enabled) {
        return new EntrypointDefinition(name, method, path, "proc", 0, enabled, "alice", null, 1, 0, 0, "alice");
    }

    private static EntrypointDefinition entrypoint(String name, String method, String path) {
        return entrypoint(name, method, path, true);
    }

    private RouteTable table(EntrypointDefinition... definitions) {
        return RouteTable.of(List.of(definitions), (definition, _) -> unroutable.add(definition.getName()));
    }

    private static RouteMatch.Found found(RouteMatch match) {
        return assertInstanceOf(RouteMatch.Found.class, match);
    }

    @Test
    public void test_literal_route_wins_over_param_route_whichever_comes_first() {
        for (final var reversed : new boolean[]{false, true}) {
            final var byId = entrypoint("byId", "GET", "/orders/{id}");
            final var latest = entrypoint("latest", "GET", "/orders/latest");
            final var table = reversed ? table(latest, byId) : table(byId, latest);

            assertEquals("latest", found(table.match("GET", List.of("orders", "latest"))).definition().getName());
        }
    }

    @Test
    public void test_param_route_serves_other_values() {
        final var table = table(entrypoint("byId", "GET", "/orders/{id}"),
                entrypoint("latest", "GET", "/orders/latest"));
        final var match = found(table.match("GET", List.of("orders", "o1")));

        assertEquals("byId", match.definition().getName());
        assertEquals(Map.of("id", "o1"), match.pathParams());
    }

    @Test
    public void test_leftmost_difference_decides() {
        final var table = table(entrypoint("kind", "GET", "/{kind}/latest/lines"),
                entrypoint("order", "GET", "/orders/{id}/lines"));

        assertEquals("order", found(table.match("GET", List.of("orders", "latest", "lines"))).definition().getName());
        assertEquals("kind", found(table.match("GET", List.of("users", "latest", "lines"))).definition().getName());
    }

    @Test
    public void test_method_not_allowed_lists_allowed_methods() {
        final var table = table(entrypoint("read", "GET", "/orders/{id}"),
                entrypoint("remove", "DELETE", "/orders/{id}"), entrypoint("other", "POST", "/users/{id}"));
        final var refused = assertInstanceOf(RouteMatch.MethodNotAllowed.class,
                table.match("PUT", List.of("orders", "o1")));

        assertEquals(new TreeSet<>(Set.of("DELETE", "GET")), refused.allowed());
    }

    @Test
    public void test_no_route() {
        final var table = table(entrypoint("read", "GET", "/orders/{id}"));

        assertInstanceOf(RouteMatch.NoRoute.class, table.match("GET", List.of("users", "u1")));
        assertInstanceOf(RouteMatch.NoRoute.class, table.match("GET", List.of("a", "b", "c")));
        assertInstanceOf(RouteMatch.NoRoute.class, RouteTable.EMPTY.match("GET", List.of()));
    }

    @Test
    public void test_disabled_definitions_are_not_routed_but_stay_known_by_name() {
        final var disabled = entrypoint("read", "GET", "/orders/{id}", false);
        final var table = table(disabled);

        assertInstanceOf(RouteMatch.NoRoute.class, table.match("GET", List.of("orders", "o1")));
        assertSame(disabled, table.definition("read"));
        assertNull(table.definition("missing"));
    }

    @Test
    public void test_unparseable_stored_template_or_method_is_skipped_and_reported() {
        final var table = table(entrypoint("bad", "GET", "orders"), entrypoint("trace", "TRACE", "/x"),
                entrypoint("good", "get", "/x"));

        assertEquals(List.of("bad", "trace"), unroutable);
        assertEquals("good", found(table.match("GET", List.of("x"))).definition().getName());
    }

    @Test
    public void test_root_route_matches_an_empty_path() {
        final var table = table(entrypoint("home", "GET", "/"));

        assertTrue(found(table.match("GET", List.of())).pathParams().isEmpty());
    }

    @Test
    public void test_query_is_routed_beside_get_on_the_same_path() {
        final var table = table(entrypoint("search", "query", "/orders"), entrypoint("list", "GET", "/orders"));

        assertEquals("search", found(table.match("QUERY", List.of("orders"))).definition().getName());
        assertEquals("list", found(table.match("GET", List.of("orders"))).definition().getName());
        assertTrue(unroutable.isEmpty());
    }
}
