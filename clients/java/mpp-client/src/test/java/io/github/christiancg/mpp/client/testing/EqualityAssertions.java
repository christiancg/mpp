package io.github.christiancg.mpp.client.testing;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

public final class EqualityAssertions {
    private EqualityAssertions() {
    }

    public static void assertDiffers(Object value, Object other) {
        assertNotEquals(value, other);
    }
}
