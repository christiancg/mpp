package org.ccg.mpp.unit.bckg_ops;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.ccg.mpp.bckg_ops.RestartablePool;
import org.ccg.mpp.log.Logger;
import org.junit.jupiter.api.Test;

public class RestartablePoolTest {
    private static final Logger logger = Logger.logFor(RestartablePoolTest.class);

    @Test
    public void test_restart_after_shutdown_accepts_new_work() throws Exception {
        final var original = Executors.newVirtualThreadPerTaskExecutor();

        final var replacement = RestartablePool.shutdownAndReplace(original, logger, "test");

        assertNotSame(original, replacement);
        assertTrue(original.isShutdown(), "the old pool must be shut down");
        final var ran = new CountDownLatch(1);
        replacement.submit(ran::countDown);
        assertTrue(ran.await(5, TimeUnit.SECONDS), "the replacement pool rejected new work");
        replacement.shutdownNow();
    }
}
