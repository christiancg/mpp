package org.ccg.mpp.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

public final class EqualityChecks {
    private EqualityChecks() {
    }

    public static void assertEqualTo(Object value, Object other) {
        assertEquals(value, other);
    }

    public static void assertDiffers(Object value, Object other) {
        assertNotEquals(value, other);
    }
}
