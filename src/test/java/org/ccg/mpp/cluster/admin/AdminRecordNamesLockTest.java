package org.ccg.mpp.cluster.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.admin.AdminRecordKey;
import org.ccg.mpp.ops.admin.CollectionOperationHelper;
import org.ccg.mpp.ops.admin.ProjectOperationHelper;
import org.ccg.mpp.ops.req.CreateCollectionRequest;
import org.ccg.mpp.ops.req.CreateProjectRequest;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class AdminRecordNamesLockTest extends AdminRecordTestBase {
    private static final String COLLECTION_NAMES = Cache.getCollectionIdentifier(TestGlobals.PROJECT,
            Globals.COLLECTION_NAMES_LOCK);
    private static final String PROJECT_NAMES = Cache.getCollectionIdentifier(Globals.ADMIN_PROJECT_NAME,
            Globals.PROJECT_NAMES_LOCK);
    private static final String FIELD = "tag";
    private static final String FIELD_INDEX = Cache.getCollectionIdentifier(TestGlobals.PROJECT, TestGlobals.COLL)
            + Globals.COLL_IDENTIFIER_SEPARATOR + FIELD;
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private long origTimeout;

    private interface Step {
        void run() throws Exception;
    }

    private final class Holder implements AutoCloseable {
        private final CountDownLatch taken = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final Thread thread;

        private Holder(String lockName, Step beforeRelease) throws InterruptedException {
            thread = Thread.ofVirtual().start(() -> {
                try {
                    locks.lockWrite(lockName);
                    try {
                        taken.countDown();
                        release.await();
                        beforeRelease.run();
                    } finally {
                        locks.releaseWrite(lockName);
                    }
                } catch (Exception | AssertionError e) {
                    failure.set(e);
                }
            });
            assertTrue(taken.await(5, TimeUnit.SECONDS));
        }

        @Override
        public void close() throws Exception {
            release.countDown();
            thread.join();
            if (failure.get() != null) {
                throw new AssertionError(failure.get());
            }
        }
    }

    @BeforeEach
    public void lowerTheLockBudget() throws Exception {
        origTimeout = config.getReplicationAckTimeoutMs();
        lockBudget(50L);
    }

    @AfterEach
    public void restoreTheLockBudget() throws Exception {
        lockBudget(origTimeout);
    }

    private void lockBudget(long millis) throws Exception {
        TestUtils.setPrivateField(config, "replicationAckTimeoutMs", millis);
    }

    private Holder holding(String lockName) throws InterruptedException {
        return new Holder(lockName, () -> {
        });
    }

    @SuppressWarnings("BusyWait")
    private static void awaitQueuedOn(String lockName) throws Exception {
        final Map<?, ?> all = TestUtils.getPrivateStaticField(ResourceLocking.class, "locks", Map.class);
        final var deadline = System.currentTimeMillis() + 5000;
        while (!(all.get(lockName) instanceof ReentrantReadWriteLock lock) || !lock.hasQueuedThreads()) {
            assertTrue(System.currentTimeMillis() < deadline, "nobody queued on " + lockName);
            Thread.sleep(5);
        }
    }

    private static CompletableFuture<AdminRecordMerge.Result> mergeInBackground(AdminRecord record) {
        return CompletableFuture.supplyAsync(() -> AdminRecordMerge.apply(List.of(record), true));
    }

    @Test
    public void test_an_anti_entropy_collection_drop_waits_for_the_collection_names_lock() throws Exception {
        final var dropped = tombstone(AdminRecordKey.collection(TestGlobals.PROJECT, TestGlobals.COLL), clock.next());
        try (var _ = holding(COLLECTION_NAMES)) {
            assertFalse(AdminRecordMerge.apply(List.of(dropped), false).complete());
            assertNotNull(cache.getAdminCollectionEntry(TestGlobals.PROJECT, TestGlobals.COLL));
        }

        assertTrue(AdminRecordMerge.apply(List.of(dropped), false).complete());
        assertNull(cache.getAdminCollectionEntry(TestGlobals.PROJECT, TestGlobals.COLL));
    }

    @Test
    public void test_a_collection_install_waits_for_the_collection_names_lock() throws Exception {
        final var record = collection(TestGlobals.PROJECT, "waiting", clock.next());
        try (var _ = holding(COLLECTION_NAMES)) {
            assertFalse(AdminRecordMerge.apply(List.of(record), true).complete());
            assertNull(cache.getAdminCollectionEntry(TestGlobals.PROJECT, "waiting"));
        }

        assertTrue(AdminRecordMerge.apply(List.of(record), true).complete());
        assertNotNull(cache.getAdminCollectionEntry(TestGlobals.PROJECT, "waiting"));
    }

    @Test
    public void test_a_collection_case_variant_registered_under_the_lock_defers_the_install() throws Exception {
        lockBudget(5000L);
        final var record = collection(TestGlobals.PROJECT, "racecoll", clock.next());
        final CompletableFuture<AdminRecordMerge.Result> merge;
        try (var _ = new Holder(COLLECTION_NAMES,
                () -> assertEquals(OperationStatus.OK, CollectionOperationHelper
                        .processCreateCollectionOperation(new CreateCollectionRequest(TestGlobals.PROJECT, "RACECOLL"))
                        .getStatus()))) {
            merge = mergeInBackground(record);
            awaitQueuedOn(COLLECTION_NAMES);
        }

        assertFalse(merge.get(5, TimeUnit.SECONDS).complete());
        assertNull(cache.getAdminCollectionEntry(TestGlobals.PROJECT, "racecoll"));
        assertNotNull(cache.getAdminCollectionEntry(TestGlobals.PROJECT, "RACECOLL"));

        assertTrue(AdminRecordMerge.apply(List.of(record), true).complete());
        assertNull(cache.getAdminCollectionEntry(TestGlobals.PROJECT, "racecoll"));
        assertNotNull(cache.getAdminCollectionEntry(TestGlobals.PROJECT, "RACECOLL"));
        assertTrue(tombstones().containsKey(record.key().id()));
    }

    @Test
    public void test_a_project_install_waits_for_the_project_names_lock() throws Exception {
        final var record = project("waitingdb", clock.next());
        try (var _ = holding(PROJECT_NAMES)) {
            assertFalse(AdminRecordMerge.apply(List.of(record), true).complete());
            assertNull(cache.getAdminProjectEntry("waitingdb"));
        }

        assertTrue(AdminRecordMerge.apply(List.of(record), true).complete());
        assertNotNull(cache.getAdminProjectEntry("waitingdb"));
    }

    @Test
    public void test_a_project_case_variant_registered_under_the_lock_defers_the_install() throws Exception {
        lockBudget(5000L);
        final var record = project("racedb", clock.next());
        final CompletableFuture<AdminRecordMerge.Result> merge;
        try (var _ = new Holder(PROJECT_NAMES, () -> assertEquals(OperationStatus.OK, ProjectOperationHelper
                .processCreateProjectOperation(new CreateProjectRequest("RACEDB"), null).getStatus()))) {
            merge = mergeInBackground(record);
            awaitQueuedOn(PROJECT_NAMES);
        }

        assertFalse(merge.get(5, TimeUnit.SECONDS).complete());
        assertNull(cache.getAdminProjectEntry("racedb"));
        assertNotNull(cache.getAdminProjectEntry("RACEDB"));

        assertTrue(AdminRecordMerge.apply(List.of(record), true).complete());
        assertNull(cache.getAdminProjectEntry("racedb"));
        assertNotNull(cache.getAdminProjectEntry("RACEDB"));
        assertTrue(tombstones().containsKey(record.key().id()));
    }

    @Test
    public void test_a_project_install_holds_its_barrier_through_registration() throws Exception {
        final var record = project(TestGlobals.PROJECT, clock.next(), "alice");
        try (var _ = holding(TestGlobals.PROJECT)) {
            assertFalse(AdminRecordMerge.apply(List.of(record), true).complete());
            assertFalse(cache.getAdminProjectEntry(TestGlobals.PROJECT).isOwner("alice"));
        }

        assertTrue(AdminRecordMerge.apply(List.of(record), true).complete());
        assertTrue(cache.getAdminProjectEntry(TestGlobals.PROJECT).isOwner("alice"));
    }

    private void indexTheCollection() {
        cache.getAdminCollectionEntry(TestGlobals.PROJECT, TestGlobals.COLL).setIndexes(new HashSet<>(Set.of(FIELD)));
    }

    @Test
    public void test_a_collection_quarantine_waits_for_its_field_index_locks() throws Exception {
        indexTheCollection();
        final var dropped = tombstone(AdminRecordKey.collection(TestGlobals.PROJECT, TestGlobals.COLL), clock.next());
        try (var _ = holding(FIELD_INDEX)) {
            assertFalse(AdminRecordMerge.apply(List.of(dropped), false).complete());
            assertNotNull(cache.getAdminCollectionEntry(TestGlobals.PROJECT, TestGlobals.COLL));
        }

        assertTrue(AdminRecordMerge.apply(List.of(dropped), false).complete());
        assertNull(cache.getAdminCollectionEntry(TestGlobals.PROJECT, TestGlobals.COLL));
    }

    @Test
    public void test_a_stale_incarnation_quarantine_waits_for_its_field_index_locks() throws Exception {
        indexTheCollection();
        cache.getAdminCollectionEntry(TestGlobals.PROJECT, TestGlobals.COLL).setIncarnation(clock.next());
        final var recreated = collection(TestGlobals.PROJECT, TestGlobals.COLL, clock.next(), clock.next());
        try (var _ = holding(FIELD_INDEX)) {
            assertFalse(AdminRecordMerge.apply(List.of(recreated), true).complete());
            assertTrue(
                    cache.getAdminCollectionEntry(TestGlobals.PROJECT, TestGlobals.COLL).getIndexes().contains(FIELD));
        }

        assertTrue(AdminRecordMerge.apply(List.of(recreated), true).complete());
        assertEquals(recreated.version(), liveVersion(recreated.key()));
    }

    @Test
    public void test_a_project_quarantine_waits_for_its_field_index_locks() throws Exception {
        indexTheCollection();
        final var dropped = tombstone(AdminRecordKey.project(TestGlobals.PROJECT), clock.next());
        try (var _ = holding(FIELD_INDEX)) {
            assertFalse(AdminRecordMerge.apply(List.of(dropped), false).complete());
            assertNotNull(cache.getAdminProjectEntry(TestGlobals.PROJECT));
        }

        assertTrue(AdminRecordMerge.apply(List.of(dropped), false).complete());
        assertNull(cache.getAdminProjectEntry(TestGlobals.PROJECT));
    }
}
