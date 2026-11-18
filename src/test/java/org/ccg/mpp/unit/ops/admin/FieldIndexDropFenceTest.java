package org.ccg.mpp.unit.ops.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.FieldIndexEntry;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.fs.IndexLogChange;
import org.ccg.mpp.fs.IndexTypeReader;
import org.ccg.mpp.fs.PendingFold;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.index.DeferredFold;
import org.ccg.mpp.ops.index.FieldIndexFolds;
import org.ccg.mpp.ops.req.DropCollectionRequest;
import org.ccg.mpp.ops.req.DropDatabaseRequest;
import org.ccg.mpp.ops.req.OperationRequest;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class FieldIndexDropFenceTest {
    private static final String FIELD = "tag";
    private static final String KEY_PADDING = "k".repeat(200);
    private static final int LOGGED_KEYS = 1000;
    private static final long DROP_GRACE_MS = 1_000L;

    private final Configuration configuration = Configuration.getInstance();
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private Long savedLockTimeout;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(new HashSet<>(Set.of(FIELD)));
        savedLockTimeout = TestUtils.getPrivateField(configuration, "transactionLockTimeoutMs", Long.class);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", savedLockTimeout);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static File collectionFolder() {
        return new File(TestGlobals.PATH + File.separator + TestGlobals.DB + File.separator + TestGlobals.COLL);
    }

    private static List<IndexLogChange> loggedKeys() {
        final var changes = new ArrayList<IndexLogChange>(LOGGED_KEYS);
        for (var i = 0; i < LOGGED_KEYS; i++) {
            changes.add(new IndexLogChange(String.format("z%05d%s", i, KEY_PADDING), List.of("n" + i), List.of()));
        }
        return changes;
    }

    private static List<FieldIndexEntry<?>> folded() {
        return List.of(new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "z" + KEY_PADDING,
                new HashSet<>(Set.of("n0"))));
    }

    private PendingFold pendingFold(IndexTypeReader source) throws Exception {
        final var pending = fs.appendIndexLogOnly(TestGlobals.DB, TestGlobals.COLL, FIELD, "String", loggedKeys(),
                source);
        assertNotNull(pending, "the log outgrew its fold threshold");
        return pending;
    }

    private static void fold(PendingFold pending) throws InterruptedException {
        FieldIndexFolds
                .runAfterUnlock(List.of(new DeferredFold(Cache.getIndexIdentifier(FIELD, String.class), pending)));
    }

    private static boolean finishesWithinGrace(CompletableFuture<OperationStatus> dropped) throws IOException {
        try {
            dropped.get(DROP_GRACE_MS, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException expected) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        } catch (ExecutionException e) {
            throw new IOException(e);
        }
    }

    private OperationStatus dropRacingAFold(OperationRequest drop) throws Exception {
        final var dropped = new CompletableFuture<OperationStatus>();
        final var droppedDuringFold = new AtomicBoolean();
        fold(pendingFold(() -> {
            CompletableFuture.runAsync(() -> dropped.complete(processor.processMessage(drop).getStatus()));
            droppedDuringFold.set(finishesWithinGrace(dropped));
            return folded();
        }));
        assertFalse(droppedDuringFold.get(), "the drop waits for a fold already past its stamp check");
        return dropped.get(10, TimeUnit.SECONDS);
    }

    @Test
    public void aCollectionDropWaitsForAnInFlightFold() throws Exception {
        assertEquals(OperationStatus.OK, dropRacingAFold(new DropCollectionRequest(TestGlobals.DB, TestGlobals.COLL)));

        assertFalse(collectionFolder().exists(), "the fold landed first, then the sweep removed everything");
        assertNull(cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void aDatabaseDropWaitsForAnInFlightFold() throws Exception {
        assertEquals(OperationStatus.OK, dropRacingAFold(new DropDatabaseRequest(TestGlobals.DB)));

        assertFalse(collectionFolder().getParentFile().exists());
        assertNull(cache.getAdminDbEntry(TestGlobals.DB));
    }

    @Test
    public void aFoldThatGetsTheFieldAfterTheDropSkipsAndCreatesNothing() throws Exception {
        final var sourceRead = new AtomicBoolean();
        final var pending = pendingFold(() -> {
            sourceRead.set(true);
            return folded();
        });

        assertEquals(OperationStatus.OK,
                processor.processMessage(new DropCollectionRequest(TestGlobals.DB, TestGlobals.COLL)).getStatus());
        fold(pending);

        assertFalse(sourceRead.get(), "a fold planned before the drop never reads its source");
        assertFalse(collectionFolder().exists(), "the skipped fold recreates no folder");
    }

    @Test
    public void aDropWhoseFieldLockBudgetRunsOutDeletesNothing() throws Exception {
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", 200L);
        final var held = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var holder = CompletableFuture.runAsync(() -> {
            try {
                locks.lockIndex(TestGlobals.DB, TestGlobals.COLL, FIELD);
                try {
                    held.countDown();
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } finally {
                    locks.releaseIndex(TestGlobals.DB, TestGlobals.COLL, FIELD);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(held.await(5, TimeUnit.SECONDS));
        try {
            assertEquals(ErrorCode.TRANSACTION_LOCK_TIMEOUT.getCode(), processor
                    .processMessage(new DropCollectionRequest(TestGlobals.DB, TestGlobals.COLL)).getErrorCode());
            assertEquals(ErrorCode.TRANSACTION_LOCK_TIMEOUT.getCode(),
                    processor.processMessage(new DropDatabaseRequest(TestGlobals.DB)).getErrorCode());
        } finally {
            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
        }

        assertTrue(new File(collectionFolder(), TestGlobals.COLL + "-0" + Globals.DB_FILE_EXTENSION).exists(),
                "nothing was deleted");
        assertNotNull(cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL));
        assertFalse(locks.holdsIndexLock(TestGlobals.DB, TestGlobals.COLL, FIELD));
    }
}
