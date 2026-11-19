package org.ccg.mpp.unit.concurrency;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.test.TestUtils;
import org.ccg.mpp.utils.ReflectionUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class ProjectBarrierLockingTest {
    private static final String PROJECT = "barrierdb";

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.releaseAllLocks();
    }

    private static Map<String, ReentrantReadWriteLock> locks(ResourceLocking rl)
            throws NoSuchFieldException, IllegalAccessException {
        final var type = new ReflectionUtils.TypeToken<Map<String, ReentrantReadWriteLock>>() {
        };
        return TestUtils.getPrivateField(rl, "locks", type);
    }

    private static boolean onAnotherThread(ResourceLockingAttempt attempt) throws Exception {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return attempt.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }).get(5, TimeUnit.SECONDS);
    }

    private interface ResourceLockingAttempt {
        boolean run() throws InterruptedException;
    }

    @Test
    public void test_shared_holders_do_not_block_each_other() throws Exception {
        final var rl = new ResourceLocking();
        assertTrue(rl.tryLockProjectShared(PROJECT, 100));

        assertTrue(onAnotherThread(() -> {
            final var acquired = rl.tryLockProjectShared(PROJECT, 100);
            rl.releaseProjectShared(PROJECT);
            return acquired;
        }));
        rl.releaseProjectShared(PROJECT);
    }

    @Test
    public void test_exclusive_times_out_while_a_shared_holder_exists() throws Exception {
        final var rl = new ResourceLocking();
        assertTrue(rl.tryLockProjectShared(PROJECT, 100));

        assertFalse(onAnotherThread(() -> rl.tryLockProjectExclusive(PROJECT, 100)));
        rl.releaseProjectShared(PROJECT);
        assertTrue(onAnotherThread(() -> {
            final var acquired = rl.tryLockProjectExclusive(PROJECT, 100);
            rl.releaseProjectExclusive(PROJECT);
            return acquired;
        }));
    }

    @Test
    public void test_shared_times_out_while_the_project_is_held_exclusively() throws Exception {
        final var rl = new ResourceLocking();
        assertTrue(rl.tryLockProjectExclusive(PROJECT, 100));

        assertFalse(onAnotherThread(() -> rl.tryLockProjectShared(PROJECT, 100)));
        rl.releaseProjectExclusive(PROJECT);
    }

    @Test
    public void test_the_project_lock_never_shares_a_key_with_a_collection_lock() throws Exception {
        final var rl = new ResourceLocking();
        assertTrue(rl.tryLockProjectExclusive(PROJECT, 100));

        assertTrue(onAnotherThread(() -> {
            final var acquired = rl.tryLockWrite(PROJECT, "coll", 100);
            rl.release(PROJECT, "coll");
            return acquired;
        }));
        rl.releaseProjectExclusive(PROJECT);
    }

    @Test
    public void test_remove_project_lock_evicts_only_when_idle() throws Exception {
        final var rl = new ResourceLocking();
        assertTrue(rl.tryLockProjectShared(PROJECT, 100));

        rl.removeProjectLock(PROJECT);
        assertNotNull(locks(rl).get(PROJECT), "a held barrier must survive eviction");

        rl.releaseProjectShared(PROJECT);
        rl.removeProjectLock(PROJECT);
        assertNull(locks(rl).get(PROJECT));
        assertTrue(rl.tryLockProjectExclusive(PROJECT, 100), "an evicted barrier must leave nothing held behind");
        rl.releaseProjectExclusive(PROJECT);
    }
}
