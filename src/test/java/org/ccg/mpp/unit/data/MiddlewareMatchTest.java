package org.ccg.mpp.unit.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.ccg.mpp.data.EntrypointDefinition;
import org.ccg.mpp.data.MiddlewareCriteria;
import org.ccg.mpp.data.MiddlewareMatch;
import org.junit.jupiter.api.Test;

public class MiddlewareMatchTest {
    private static final EntrypointDefinition LIST_ORDERS = entrypoint("listOrders", "GET", "/orders");
    private static final EntrypointDefinition GET_ORDER = entrypoint("getOrder", "GET", "/orders/{id}");
    private static final EntrypointDefinition CREATE_ORDER = entrypoint("createOrder", "POST", "/orders");
    private static final EntrypointDefinition ARCHIVE = entrypoint("archive", "GET", "/orders-archive");
    private static final EntrypointDefinition TENANT = entrypoint("tenant", "GET", "/{tenant}/orders");
    private static final EntrypointDefinition ROOT = entrypoint("root", "GET", "/");

    private static EntrypointDefinition entrypoint(String name, String method, String path) {
        return new EntrypointDefinition(name, method, path, "p", 0L, true, "alice", null, 1L, 0L, 0L, "alice");
    }

    private static MiddlewareCriteria prefix(String pathPrefix) {
        return new MiddlewareCriteria(List.of(), pathPrefix, List.of());
    }

    private static MiddlewareCriteria names(String... entrypoints) {
        return new MiddlewareCriteria(List.of(), null, List.of(entrypoints));
    }

    @Test
    public void test_everything_wraps_every_entrypoint() {
        for (final var entrypoint : List.of(LIST_ORDERS, GET_ORDER, CREATE_ORDER, ARCHIVE, TENANT, ROOT)) {
            assertTrue(MiddlewareMatch.EVERYTHING.wraps(entrypoint), entrypoint.getName());
        }
        assertTrue(MiddlewareMatch.EVERYTHING.isEverything());
    }

    @Test
    public void test_a_prefix_covers_whole_segments_only() {
        final var match = new MiddlewareMatch(prefix("/orders"), List.of());

        assertTrue(match.wraps(LIST_ORDERS));
        assertTrue(match.wraps(GET_ORDER));
        assertFalse(match.wraps(ARCHIVE));
        assertFalse(match.wraps(ROOT));
    }

    @Test
    public void test_a_literal_never_matches_a_parameter_segment() {
        assertFalse(new MiddlewareMatch(prefix("/orders"), List.of()).wraps(TENANT));
        assertFalse(new MiddlewareMatch(prefix("/orders/latest"), List.of()).wraps(GET_ORDER));
    }

    @Test
    public void test_the_root_prefix_covers_everything() {
        assertTrue(new MiddlewareMatch(prefix("/"), List.of()).wraps(TENANT));
    }

    @Test
    public void test_criteria_are_anded() {
        final var match = new MiddlewareMatch(new MiddlewareCriteria(List.of("GET"), "/orders", List.of("getOrder")),
                List.of());

        assertTrue(match.wraps(GET_ORDER));
        assertFalse(match.wraps(LIST_ORDERS));
        assertFalse(match.wraps(CREATE_ORDER));
    }

    @Test
    public void test_methods_and_names_compare_exactly() {
        assertFalse(new MiddlewareMatch(new MiddlewareCriteria(List.of("POST"), null, List.of()), List.of())
                .wraps(GET_ORDER));
        assertFalse(new MiddlewareMatch(names("GetOrder"), List.of()).wraps(GET_ORDER));
    }

    @Test
    public void test_exclude_by_name_leaves_one_entrypoint_out() {
        final var match = new MiddlewareMatch(prefix("/orders"), List.of(names("listOrders")));

        assertTrue(match.wraps(GET_ORDER));
        assertTrue(match.wraps(CREATE_ORDER));
        assertFalse(match.wraps(LIST_ORDERS));
    }

    @Test
    public void test_exclude_rules_are_ored() {
        final var match = new MiddlewareMatch(MiddlewareCriteria.ANY, List.of(names("listOrders"), prefix("/{x}")));

        assertFalse(match.wraps(LIST_ORDERS));
        assertTrue(match.wraps(GET_ORDER));
        assertFalse(new MiddlewareMatch(MiddlewareCriteria.ANY, List.of(names("listOrders"), names("getOrder")))
                .wraps(GET_ORDER));
    }

    @Test
    public void test_exclude_rule_criteria_are_anded() {
        final var match = new MiddlewareMatch(MiddlewareCriteria.ANY,
                List.of(new MiddlewareCriteria(List.of("GET"), "/orders", List.of())));

        assertFalse(match.wraps(GET_ORDER));
        assertFalse(match.wraps(LIST_ORDERS));
        assertTrue(match.wraps(CREATE_ORDER));
    }

    @Test
    public void test_excluding_an_uncovered_entrypoint_changes_nothing() {
        final var match = new MiddlewareMatch(prefix("/orders"), List.of(names("root")));

        assertFalse(match.wraps(ROOT));
        assertTrue(match.wraps(GET_ORDER));
    }

    @Test
    public void test_round_trips_exclude_through_json() {
        final var match = new MiddlewareMatch(new MiddlewareCriteria(List.of("GET", "POST"), "/orders", List.of()),
                List.of(names("listOrders"), new MiddlewareCriteria(List.of("GET"), "/orders/public", List.of())));

        assertEquals(match, MiddlewareMatch.fromJsonObject(match.toJsonObject()));
    }

    @Test
    public void test_an_empty_exclude_is_not_written() {
        final var json = new MiddlewareMatch(prefix("/orders"), List.of()).toJsonObject();

        assertFalse(json.has("exclude"));
        assertEquals("/orders", json.get("pathPrefix").asJsonString().getValue());
        assertEquals(MiddlewareMatch.EVERYTHING, MiddlewareMatch.fromJsonObject(null));
        assertEquals(MiddlewareCriteria.ANY, MiddlewareCriteria.fromJsonObject(null));
    }
}
