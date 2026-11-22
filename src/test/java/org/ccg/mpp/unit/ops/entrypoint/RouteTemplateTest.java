package org.ccg.mpp.unit.ops.entrypoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.ccg.mpp.ops.entrypoint.InvalidRouteException;
import org.ccg.mpp.ops.entrypoint.RouteTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class RouteTemplateTest {

    private static RouteTemplate route(String template) throws InvalidRouteException {
        return RouteTemplate.parse(template);
    }

    @Test
    public void test_parses_literals_and_params() throws InvalidRouteException {
        final var template = route("/orders/{id}/lines");

        assertEquals("/orders/{id}/lines", template.normalized());
        assertEquals(List.of(new RouteTemplate.Literal("orders"), new RouteTemplate.Param("id"),
                new RouteTemplate.Literal("lines")), template.segments());
    }

    @Test
    public void test_root_and_trailing_slash_normalise() throws InvalidRouteException {
        assertEquals("/", route("/").normalized());
        assertTrue(route("/").segments().isEmpty());
        assertEquals("/orders", route("/orders/").normalized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"orders", "", "//", "/orders//lines", "/orders/{id}x", "/orders/x{id}", "/{1d}", "/a%2Fb",
            "/a b", "/a?b", "/{}", "/café"})
    public void test_refuses_malformed_templates(String template) {
        assertThrows(InvalidRouteException.class, () -> route(template));
    }

    @Test
    public void test_refuses_a_missing_template() {
        assertThrows(InvalidRouteException.class, () -> route(null));
    }

    @Test
    public void test_refuses_duplicate_param() {
        final var failure = assertThrows(InvalidRouteException.class, () -> route("/{id}/x/{id}"));

        assertTrue(failure.getMessage().contains("'id' twice"), failure.getMessage());
    }

    @Test
    public void test_refuses_over_max_segments_and_chars() throws InvalidRouteException {
        assertEquals(16, route("/a".repeat(16)).segments().size());
        assertThrows(InvalidRouteException.class, () -> route("/a".repeat(17)));
        assertThrows(InvalidRouteException.class, () -> route("/" + "a".repeat(256)));
        assertEquals(256, route("/" + "a".repeat(255)).normalized().length());
    }

    @Test
    public void test_matches_and_binds_params() throws InvalidRouteException {
        final var template = route("/orders/{id}/lines/{line}");

        assertEquals(Map.of("id", "o1", "line", "3"), template.match(List.of("orders", "o1", "lines", "3")));
        assertNull(template.match(List.of("orders", "o1", "items", "3")));
    }

    @Test
    public void test_segment_count_must_equal() throws InvalidRouteException {
        assertNull(route("/orders/{id}").match(List.of("orders")));
        assertNull(route("/orders/{id}").match(List.of("orders", "o1", "x")));
        assertEquals(Map.of(), route("/").match(List.of()));
    }

    @Test
    public void test_same_shape_ignores_param_names() throws InvalidRouteException {
        assertTrue(route("/orders/{id}").sameShape(route("/orders/{orderId}")));
        assertTrue(route("/").sameShape(route("/")));
    }

    @Test
    public void test_literal_and_param_are_not_same_shape() throws InvalidRouteException {
        assertFalse(route("/orders/{id}").sameShape(route("/orders/latest")));
        assertFalse(route("/orders/a").sameShape(route("/orders/b")));
        assertFalse(route("/orders/{id}").sameShape(route("/orders/{id}/lines")));
    }

    @Test
    public void test_literal_outranks_param_at_first_differing_position() throws InvalidRouteException {
        assertTrue(route("/orders/latest").compareSpecificity(route("/orders/{id}")) < 0);
        assertTrue(route("/orders/{id}").compareSpecificity(route("/orders/latest")) > 0);
        assertTrue(route("/orders/{id}/lines").compareSpecificity(route("/{kind}/latest/lines")) < 0);
        assertTrue(route("/a/x").compareSpecificity(route("/b/x")) < 0);
        assertEquals(0, route("/orders/{id}").compareSpecificity(route("/orders/{other}")));
    }
}
