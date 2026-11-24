package org.ccg.mpp.unit.ops.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.ccg.mpp.ops.middleware.InvalidMiddlewareException;
import org.ccg.mpp.ops.middleware.PathPrefix;
import org.junit.jupiter.api.Test;

public class PathPrefixTest {
    @Test
    public void test_normalizes_a_trailing_slash() throws Exception {
        assertEquals("/orders", PathPrefix.normalized("/orders/", "match.pathPrefix"));
        assertEquals("/", PathPrefix.normalized("/", "match.pathPrefix"));
        assertEquals("/orders/public", PathPrefix.normalized("/orders/public", "match.pathPrefix"));
    }

    @Test
    public void test_refuses_a_parameter_in_the_prefix() {
        final var refused = assertThrows(InvalidMiddlewareException.class,
                () -> PathPrefix.normalized("/{tenant}", "match.pathPrefix"));
        assertTrue(refused.getMessage().contains("{tenant}"), refused.getMessage());
        assertTrue(refused.getMessage().startsWith("match.pathPrefix is not valid: "), refused.getMessage());
    }

    @Test
    public void test_refuses_an_invalid_template() {
        assertThrows(InvalidMiddlewareException.class, () -> PathPrefix.normalized("orders", "match.pathPrefix"));
        assertThrows(InvalidMiddlewareException.class, () -> PathPrefix.normalized("/a//b", "match.pathPrefix"));
        assertThrows(InvalidMiddlewareException.class, () -> PathPrefix.normalized(null, "match.pathPrefix"));
        assertThrows(InvalidMiddlewareException.class, () -> PathPrefix.normalized("/a b", "match.pathPrefix"));
    }
}
