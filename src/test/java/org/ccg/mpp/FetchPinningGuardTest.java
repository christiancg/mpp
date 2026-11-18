package org.ccg.mpp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.ccg.mpp.ex.InvalidConfigurationException;
import org.ccg.mpp.simplejs.host.PinningResolver;
import org.junit.jupiter.api.Test;

public class FetchPinningGuardTest {
    @Test
    public void test_fetch_without_an_active_pinning_resolver_refuses_to_start() {
        assertThrows(InvalidConfigurationException.class,
                () -> FetchPinningGuard.refuseUnpinnedFetch(true, () -> false));
    }

    @Test
    public void test_fetch_disabled_or_pinned_starts() {
        assertDoesNotThrow(() -> FetchPinningGuard.refuseUnpinnedFetch(false, () -> false));
        assertDoesNotThrow(() -> FetchPinningGuard.refuseUnpinnedFetch(true, () -> true));
    }

    @Test
    public void test_the_shipped_classpath_installs_the_pinning_resolver() {
        assertTrue(PinningResolver.isInstalled());
        FetchPinningGuard.refuseUnpinnedFetch();
    }
}
