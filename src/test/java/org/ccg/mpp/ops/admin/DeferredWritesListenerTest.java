package org.ccg.mpp.ops.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.ccg.mpp.bckg_ops.BackgroundTaskManager;
import org.ccg.mpp.bckg_ops.PendingIndexWrites;
import org.ccg.mpp.bckg_ops.events.IndexRepairEvent;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.PkIndexEntry;
import org.ccg.mpp.data.admin.AdminPageEntry;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.req.SaveRequest;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

public class DeferredWritesListenerTest {
    private final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);
    private final BackgroundTaskManager taskManager = IocContainer.get(BackgroundTaskManager.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final DeferredWrites listener = new DeferredWrites();
    private final Cache cache = IocContainer.get(Cache.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateStaticField(PageOccupancyReconciler.class, "taskManager", taskManager);
        TestUtils.setPrivateStaticField(AdminOperationHelper.class, "fs", fs);
        pendingIndexWrites.clearCollection(TestGlobals.PROJECT, TestGlobals.COLL);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @Test
    public void aFailedReplayKeepsItsMarksAndQueuesTheRepair() throws Exception {
        final var queued = Mockito.mock(BackgroundTaskManager.class);
        TestUtils.setPrivateStaticField(PageOccupancyReconciler.class, "taskManager", queued);
        final var rows = List.of(new PkIndexEntry(TestGlobals.PROJECT, TestGlobals.COLL, "partly", 0L, 10L, 1L, 1L));

        final var generation = listener.replaying(TestGlobals.PROJECT, TestGlobals.COLL, rows);
        listener.notReplayed(TestGlobals.PROJECT, TestGlobals.COLL, rows, generation);

        assertTrue(pendingIndexWrites.idsFor(TestGlobals.PROJECT, TestGlobals.COLL).contains("partly"),
                "redo may have written some of its bytes, so the index work stays outstanding");
        Mockito.verify(queued).submitMaintenance(ArgumentMatchers.any(IndexRepairEvent.class));
    }

    @Test
    public void theRedriveAppliesTransactionRecordsOnlyUnderTheirLock() throws Exception {
        final var spy = Mockito.spy(fs);
        TestUtils.setPrivateStaticField(AdminOperationHelper.class, "fs", spy);
        final var held = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var holder = Thread.ofPlatform().start(() -> {
            try {
                locks.lock(Globals.ADMIN_PROJECT_NAME, Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME);
                held.countDown();
                release.await();
                locks.release(Globals.ADMIN_PROJECT_NAME, Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(held.await(5, TimeUnit.SECONDS));

        final var started = System.nanoTime();
        AdminOperationHelper.applyDeferredTransactionRecords(100L);
        final var waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        release.countDown();
        holder.join(5_000L);

        assertTrue(waitedMillis < 2_000L, "the wait is budgeted");
        Mockito.verify(spy, Mockito.never()).applyDeferredWrites(Globals.ADMIN_PROJECT_NAME,
                Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME);
        AdminOperationHelper.applyDeferredTransactionRecords(100L);
        Mockito.verify(spy).applyDeferredWrites(Globals.ADMIN_PROJECT_NAME, Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME);
        assertFalse(locks.isWriteLockedByCurrentThread(Globals.ADMIN_PROJECT_NAME + Globals.COLL_IDENTIFIER_SEPARATOR
                + Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME));
        assertEquals(0, held.getCount());
    }

    @Test
    public void staleRowsAreReconciledOnTheCollectionsNextUse() {
        saveOne();
        final var id = TestGlobals.PROJECT + Globals.COLL_IDENTIFIER_SEPARATOR + TestGlobals.COLL;
        final var row = cache.getAdminPageEntries(TestGlobals.PROJECT, TestGlobals.COLL).getFirst();
        row.setEntryCount(row.getEntryCount() + 1);

        DeferredWrites.reconcileRowsOnNextUse(TestGlobals.PROJECT, TestGlobals.COLL);
        assertTrue(listener.pending(id), "a collection whose rows could not be reconciled waits for its next use");
        listener.apply(id);

        assertFalse(listener.pending(id));
        assertEquals(1, entryCount());
    }

    @Test
    public void aReconcileThatStillFailsStaysPendingForTheNextUse() throws Exception {
        saveOne();
        final var id = TestGlobals.PROJECT + Globals.COLL_IDENTIFIER_SEPARATOR + TestGlobals.COLL;
        final var row = cache.getAdminPageEntries(TestGlobals.PROJECT, TestGlobals.COLL).getFirst();
        row.setEntryCount(row.getEntryCount() + 1);
        final var failing = Mockito.spy(fs);
        Mockito.doThrow(new IOException("No space left on device")).when(failing)
                .readWholePkIndexFile(TestGlobals.PROJECT, TestGlobals.COLL);
        DeferredWrites.reconcileRowsOnNextUse(TestGlobals.PROJECT, TestGlobals.COLL);
        TestUtils.setPrivateStaticField(PageOccupancyReconciler.class, "fs", failing);
        try {
            listener.apply(id);
            assertTrue(listener.pending(id), "a failed reconcile is retried on the next use, not left to a restart");
        } finally {
            TestUtils.setPrivateStaticField(PageOccupancyReconciler.class, "fs", fs);
        }
        listener.apply(id);
        assertFalse(listener.pending(id));
        assertEquals(1, entryCount());
    }

    @Test
    public void aReplayLeavesTheRowsDueForTheNextUseInUserProjectsOnly() {
        final var rows = List.of(new PkIndexEntry(TestGlobals.PROJECT, TestGlobals.COLL, "replayed", 0L, 10L, 1L, 1L));
        final var id = TestGlobals.PROJECT + Globals.COLL_IDENTIFIER_SEPARATOR + TestGlobals.COLL;
        final var adminId = Globals.ADMIN_PROJECT_NAME + Globals.COLL_IDENTIFIER_SEPARATOR
                + Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME;

        listener.replayed(TestGlobals.PROJECT, TestGlobals.COLL, rows,
                listener.replaying(TestGlobals.PROJECT, TestGlobals.COLL, rows));
        listener.replayed(Globals.ADMIN_PROJECT_NAME, Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME, List.of(), 0L);

        assertTrue(listener.pending(id), "a replay outside the lock hook leaves the rows to the next use");
        assertFalse(listener.pending(adminId));
        listener.apply(id);
        assertFalse(listener.pending(id));
    }

    @Test
    public void aDropForgetsTheStaleRowsOfWhatItDropped() {
        final var id = TestGlobals.PROJECT + Globals.COLL_IDENTIFIER_SEPARATOR + TestGlobals.COLL;
        final var sibling = TestGlobals.PROJECT + Globals.COLL_IDENTIFIER_SEPARATOR + "sibling";
        final var otherProject = "otherProject" + Globals.COLL_IDENTIFIER_SEPARATOR + TestGlobals.COLL;

        DeferredWrites.reconcileRowsOnNextUse(TestGlobals.PROJECT, TestGlobals.COLL);
        DeferredWrites.forgetCollection(TestGlobals.PROJECT, TestGlobals.COLL);
        assertFalse(listener.pending(id));

        DeferredWrites.reconcileRowsOnNextUse(TestGlobals.PROJECT, TestGlobals.COLL);
        DeferredWrites.reconcileRowsOnNextUse(TestGlobals.PROJECT, "sibling");
        DeferredWrites.reconcileRowsOnNextUse("otherProject", TestGlobals.COLL);
        DeferredWrites.forgetProject(TestGlobals.PROJECT);
        assertFalse(listener.pending(id));
        assertFalse(listener.pending(sibling));
        assertTrue(listener.pending(otherProject), "only the dropped project's collections are forgotten");
        DeferredWrites.forgetProject("otherProject");
        assertFalse(listener.pending(otherProject));
    }

    private void saveOne() {
        final var request = new SaveRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        final var object = new JsonObject();
        object.addProperty("_id", "stale");
        request.setObject(object);
        request.set_id("stale");
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
    }

    private int entryCount() {
        return cache.getAdminPageEntries(TestGlobals.PROJECT, TestGlobals.COLL).stream()
                .mapToInt(AdminPageEntry::getEntryCount).sum();
    }
}
