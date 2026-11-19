package org.ccg.mpp.unit.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.concurrency.SettleWindow;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class ResourceLockingSettleWindowTest {
    private static final String PROJECT = "settle_project";
    private static final String COLL = "settle_coll";
    private static final String ID = Cache.getCollectionIdentifier(PROJECT, COLL);
    private static final long WAIT_MS = 5_000L;
    private static final long BLOCKED_MS = 200L;
    private final ResourceLocking rl = new ResourceLocking();

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
    }

    private interface Locked {
        boolean acquire() throws Exception;
    }

    private static CompletableFuture<Boolean> inOtherThread(Locked locked, Runnable release) {
        final var result = new CompletableFuture<Boolean>();
        Thread.ofPlatform().start(() -> {
            try {
                final var acquired = locked.acquire();
                if (acquired) {
                    release.run();
                }
                result.complete(acquired);
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    private CompletableFuture<Boolean> pointRead(long timeoutMillis) {
        return inOtherThread(() -> rl.acquirePointReadLock(false, ID, timeoutMillis) != null,
                () -> rl.releaseRead(PROJECT, COLL));
    }

    private CompletableFuture<Boolean> scanRead(long timeoutMillis) {
        return inOtherThread(() -> rl.acquireReadLocks(false, List.of(ID), timeoutMillis) != null,
                () -> rl.releaseRead(PROJECT, COLL));
    }

    private SettleWindow openWindow() throws InterruptedException {
        rl.lock(PROJECT, COLL);
        return rl.openSettleWindow(PROJECT, COLL);
    }

    private static void assertStillBlocked(CompletableFuture<Boolean> pending) {
        try {
            pending.get(BLOCKED_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException expected) {
            return;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        throw new AssertionError("the acquisition went through an open window");
    }

    @Test
    public void test_a_point_read_proceeds_while_a_window_is_open() throws Exception {
        try (var window = openWindow()) {
            assertTrue(window.shared());
            assertTrue(pointRead(0L).get(WAIT_MS, TimeUnit.MILLISECONDS));
            assertEquals(List.of(), rl.acquirePointReadLock(true, ID, 0L), "a dirty read takes no lock");
        }
    }

    @Test
    public void test_a_writer_also_holding_a_read_lock_keeps_its_lock_exclusive() throws Exception {
        rl.lock(PROJECT, COLL);
        rl.lockRead(PROJECT, COLL);
        try (var window = rl.openSettleWindow(PROJECT, COLL)) {
            assertFalse(window.shared(), "downgrading would leave the read hold counted twice");
        } finally {
            rl.releaseRead(PROJECT, COLL);
            rl.release(PROJECT, COLL);
        }
    }

    @Test
    public void test_a_scan_read_waits_until_the_window_closes() throws Exception {
        final CompletableFuture<Boolean> scan;
        try (var ignored = openWindow()) {
            scan = scanRead(0L);
            assertStillBlocked(scan);
        }
        assertTrue(scan.get(WAIT_MS, TimeUnit.MILLISECONDS));
    }

    @Test
    public void test_a_scan_queued_before_the_window_backs_off_onto_the_gate() throws Exception {
        rl.lock(PROJECT, COLL);
        final var acquired = new AtomicBoolean();
        final var scan = inOtherThread(() -> {
            rl.lockRead(PROJECT, COLL);
            acquired.set(true);
            return true;
        }, () -> rl.releaseRead(PROJECT, COLL));
        Thread.sleep(BLOCKED_MS);
        try (var ignored = rl.openSettleWindow(PROJECT, COLL)) {
            assertStillBlocked(scan);
            assertFalse(acquired.get());
        }
        assertTrue(scan.get(WAIT_MS, TimeUnit.MILLISECONDS));
    }

    @Test
    public void test_a_bounded_scan_read_times_out_on_an_open_window() throws Exception {
        try (var ignored = openWindow()) {
            assertFalse(scanRead(150L).get(WAIT_MS, TimeUnit.MILLISECONDS));
            assertFalse(inOtherThread(() -> rl.tryLockRead(PROJECT, COLL, 150L), () -> rl.releaseRead(PROJECT, COLL))
                    .get(WAIT_MS, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    public void test_try_lock_write_fails_inside_a_window() throws Exception {
        try (var ignored = openWindow()) {
            assertFalse(inOtherThread(() -> rl.tryLockWrite(PROJECT, COLL), () -> rl.release(PROJECT, COLL))
                    .get(WAIT_MS, TimeUnit.MILLISECONDS));
            assertFalse(inOtherThread(() -> rl.tryLockWrite(PROJECT, COLL, 150L), () -> rl.release(PROJECT, COLL))
                    .get(WAIT_MS, TimeUnit.MILLISECONDS));
        }
        assertTrue(inOtherThread(() -> rl.tryLockWrite(PROJECT, COLL), () -> rl.release(PROJECT, COLL)).get(WAIT_MS,
                TimeUnit.MILLISECONDS));
    }

    @Test
    public void test_a_failing_window_holds_point_reads_and_hands_the_write_lock_to_its_owner() throws Exception {
        final var opened = new CountDownLatch(1);
        final var failNow = new CountDownLatch(1);
        final var failed = new CountDownLatch(1);
        final var closeNow = new CountDownLatch(1);
        final var ownerHeldWrite = new AtomicBoolean();
        final var owner = Thread.ofPlatform().start(() -> {
            try (var window = openWindow()) {
                opened.countDown();
                failNow.await();
                window.fail();
                window.fail();
                ownerHeldWrite.set(rl.isWriteLockedByCurrentThread(ID) && !window.shared());
                failed.countDown();
                closeNow.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(opened.await(WAIT_MS, TimeUnit.MILLISECONDS));
        final var readerHolds = new CountDownLatch(1);
        final var readerRelease = new CountDownLatch(1);
        final var reader = inOtherThread(() -> {
            final var acquired = rl.acquirePointReadLock(false, ID, 0L) != null;
            readerHolds.countDown();
            readerRelease.await();
            return acquired;
        }, () -> rl.releaseRead(PROJECT, COLL));
        assertTrue(readerHolds.await(WAIT_MS, TimeUnit.MILLISECONDS));

        failNow.countDown();
        assertFalse(failed.await(BLOCKED_MS, TimeUnit.MILLISECONDS), "fail waits for the point reader inside");
        assertFalse(pointRead(150L).get(WAIT_MS, TimeUnit.MILLISECONDS), "a failing window holds new point reads");
        readerRelease.countDown();
        assertTrue(reader.get(WAIT_MS, TimeUnit.MILLISECONDS));
        assertTrue(failed.await(WAIT_MS, TimeUnit.MILLISECONDS));
        assertTrue(ownerHeldWrite.get());

        closeNow.countDown();
        owner.join(WAIT_MS);
        assertTrue(pointRead(0L).get(WAIT_MS, TimeUnit.MILLISECONDS));
    }

    @Test
    public void test_a_reentrant_write_hold_stays_exclusive() throws Exception {
        rl.lock(PROJECT, COLL);
        rl.lock(PROJECT, COLL);
        try (var window = rl.openSettleWindow(PROJECT, COLL)) {
            assertFalse(window.shared());
            window.fail();
            assertFalse(pointRead(150L).get(WAIT_MS, TimeUnit.MILLISECONDS));
        }
        try (var exclusive = SettleWindow.exclusive()) {
            assertFalse(exclusive.shared());
        }
        assertTrue(rl.isWriteLockedByCurrentThread(ID));
        rl.release(PROJECT, COLL);
        rl.release(PROJECT, COLL);
        assertFalse(rl.isWriteLockedByCurrentThread(ID));
        try (var unlocked = rl.openSettleWindow(PROJECT, COLL)) {
            assertFalse(unlocked.shared(), "no window without the write lock");
        }
    }

    @Test
    public void test_window_calls_from_another_thread_release_nothing() throws Exception {
        try (var window = openWindow()) {
            inOtherThread(() -> {
                window.fail();
                window.close();
                return true;
            }, () -> {
            }).get(WAIT_MS, TimeUnit.MILLISECONDS);
            assertTrue(window.shared());
            assertStillBlocked(scanRead(0L));
            assertTrue(pointRead(0L).get(WAIT_MS, TimeUnit.MILLISECONDS));
        }
        assertTrue(scanRead(0L).get(WAIT_MS, TimeUnit.MILLISECONDS));
    }
}
