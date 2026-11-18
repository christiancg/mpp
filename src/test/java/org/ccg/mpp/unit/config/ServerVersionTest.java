package org.ccg.mpp.unit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.ccg.mpp.config.ServerVersion;
import org.junit.jupiter.api.Test;

public class ServerVersionTest {
    @Test
    public void test_current_is_the_unpackaged_marker_outside_a_jar() {
        assertEquals(ServerVersion.UNPACKAGED, ServerVersion.current());
    }
}
