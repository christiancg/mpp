package org.ccg.mpp.unit.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.ccg.mpp.data.MiddlewareCriteria;
import org.ccg.mpp.data.MiddlewareDefinition;
import org.ccg.mpp.data.MiddlewareMatch;
import org.ccg.mpp.data.MiddlewarePhase;
import org.junit.jupiter.api.Test;

public class MiddlewareDefinitionTest {
    private static MiddlewareDefinition auth(MiddlewareMatch match, boolean enabled) {
        return new MiddlewareDefinition("auth", MiddlewarePhase.INGRESS, 10, match, "checkToken", 2000L, enabled,
                "alice", "Bearer filter").stamped(3L, 100L, 200L, "alice");
    }

    @Test
    public void test_round_trips_through_json() {
        final var match = new MiddlewareMatch(new MiddlewareCriteria(List.of("POST"), "/orders", List.of()),
                List.of(new MiddlewareCriteria(List.of(), null, List.of("listOrders"))));
        final var definition = auth(match, true);
        final var copy = MiddlewareDefinition.fromJsonObject(definition.toJsonObject());

        assertEquals(definition, copy);
        assertEquals(definition.hashCode(), copy.hashCode());
        assertEquals(MiddlewarePhase.INGRESS, copy.getPhase());
        assertEquals(10, copy.getOrder());
        assertEquals("/orders", copy.getMatch().include().pathPrefix());
        assertEquals(List.of("listOrders"), copy.getMatch().exclude().getFirst().entrypoints());
        assertEquals("checkToken", copy.getProcedureName());
        assertEquals(2000L, copy.getTimeoutMs());
        assertEquals("alice", copy.getDefiner());
        assertEquals("Bearer filter", copy.getDescription());
        assertEquals(3L, copy.getVersion());
        assertTrue(copy.toString().contains("auth"));
    }

    @Test
    public void test_an_empty_match_writes_no_match_key() {
        final var json = auth(MiddlewareMatch.EVERYTHING, true).toJsonObject();

        assertFalse(json.has("match"));
        assertEquals(MiddlewareMatch.EVERYTHING, MiddlewareDefinition.fromJsonObject(json).getMatch());
    }

    @Test
    public void test_skipped_and_revoked_are_distinct() {
        final var userDisabled = auth(MiddlewareMatch.EVERYTHING, false);
        final var revoked = auth(MiddlewareMatch.EVERYTHING, true).disabledCopy("definer lost RUN", 300L);

        assertTrue(userDisabled.isSkipped());
        assertFalse(userDisabled.isRevoked());
        assertTrue(revoked.isRevoked());
        assertFalse(revoked.isSkipped());
        assertFalse(auth(MiddlewareMatch.EVERYTHING, true).isSkipped());
        assertFalse(auth(MiddlewareMatch.EVERYTHING, true).isRevoked());
    }

    @Test
    public void test_disabled_copy_keeps_everything_else() {
        final var original = auth(MiddlewareMatch.EVERYTHING, true);
        final var disabled = original.disabledCopy("gone", 300L);

        assertNotEquals(original, disabled);
        assertEquals(original.getVersion() + 1, disabled.getVersion());
        assertEquals("gone", disabled.getDisabledReason());
        assertEquals(original.getProcedureName(), disabled.getProcedureName());
        assertEquals(original.getMatch(), disabled.getMatch());
        assertEquals(300L, disabled.getUpdatedAt());
    }

    @Test
    public void test_equality_covers_each_field() {
        final var base = auth(MiddlewareMatch.EVERYTHING, true);
        assertEquals(base, base);
        assertNotEquals(base, new MiddlewareDefinition("auth", MiddlewarePhase.EGRESS, 10, MiddlewareMatch.EVERYTHING,
                "checkToken", 2000L, true, "alice", "Bearer filter").stamped(3L, 100L, 200L, "alice"));
    }
}
