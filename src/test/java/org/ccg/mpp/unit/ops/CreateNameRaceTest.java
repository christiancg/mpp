package org.ccg.mpp.unit.ops;

import static org.ccg.mpp.test.LockHolder.startParked;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.admin.CollectionOperationHelper;
import org.ccg.mpp.ops.admin.ProjectOperationHelper;
import org.ccg.mpp.ops.admin.RestampedRecords;
import org.ccg.mpp.ops.req.CreateCollectionRequest;
import org.ccg.mpp.ops.req.CreateProjectRequest;
import org.ccg.mpp.ops.req.DropCollectionRequest;
import org.ccg.mpp.ops.resp.OperationResponse;
import org.ccg.mpp.test.LockHolder;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class CreateNameRaceTest {
    private static final String CASE_VARIANT = "MyCollection";
    private static final long SHORT_BUDGET_MS = 100L;
    private static final long LONG_BUDGET_MS = 10_000L;
    private static final Configuration configuration = Configuration.getInstance();
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final LockHolder holder = new LockHolder();
    private final ExecutorService other = Executors.newVirtualThreadPerTaskExecutor();
    private final long originalTimeout = configuration.getTransactionLockTimeoutMs();

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", LONG_BUDGET_MS);
    }

    @AfterEach
    public void tearDown() throws Exception {
        holder.close();
        other.shutdownNow();
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", originalTimeout);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void holdNameLock(String projectName, String lockName) throws Exception {
        holder.hold(() -> locks.tryLockWrite(projectName, lockName, LONG_BUDGET_MS),
                () -> locks.release(projectName, lockName));
    }

    private boolean namesLockFreeElsewhere() throws Exception {
        return holder.run(() -> {
            final var acquired = locks.tryLockWrite(TestGlobals.PROJECT, Globals.COLLECTION_NAMES_LOCK, 0);
            if (acquired) {
                locks.release(TestGlobals.PROJECT, Globals.COLLECTION_NAMES_LOCK);
            }
            return acquired;
        });
    }

    private static OperationResponse dropCollection() {
        return CollectionOperationHelper.processDropCollectionOperation(
                new DropCollectionRequest(TestGlobals.PROJECT, TestGlobals.COLL), new RestampedRecords());
    }

    private static OperationResponse createCollection(String collName) {
        return CollectionOperationHelper
                .processCreateCollectionOperation(new CreateCollectionRequest(TestGlobals.PROJECT, collName));
    }

    private static OperationResponse createProject(String projectName) {
        return ProjectOperationHelper.processCreateProjectOperation(new CreateProjectRequest(projectName), null);
    }

    @Test
    public void test_a_collection_case_variant_created_concurrently_is_refused() throws Exception {
        holdNameLock(TestGlobals.PROJECT, Globals.COLLECTION_NAMES_LOCK);
        final var raced = new AtomicReference<OperationResponse>();
        final var racer = startParked(() -> createCollection("racedocs"), raced);

        assertEquals(OperationStatus.OK, holder.run(() -> createCollection("RaceDocs")).getStatus());
        holder.release();
        racer.join(LONG_BUDGET_MS);

        assertEquals(ErrorCode.NAME_COLLIDES_ON_DISK.getCode(), raced.get().getErrorCode(),
                "the create that waited must see the variant registered while it waited");
        assertNotNull(cache.getAdminCollectionEntry(TestGlobals.PROJECT, "RaceDocs"));
        assertNull(cache.getAdminCollectionEntry(TestGlobals.PROJECT, "racedocs"));
    }

    @Test
    public void test_a_collection_registration_lock_timeout_answers_409_5() throws Exception {
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", SHORT_BUDGET_MS);
        holdNameLock(TestGlobals.PROJECT, Globals.COLLECTION_NAMES_LOCK);

        final var response = createCollection("timedout");

        assertEquals(ErrorCode.TRANSACTION_LOCK_TIMEOUT.getCode(), response.getErrorCode());
        assertNull(cache.getAdminCollectionEntry(TestGlobals.PROJECT, "timedout"));
        assertFalse(new File(TestGlobals.PATH + File.separator + TestGlobals.PROJECT + File.separator + "timedout")
                .exists(), "a create that never got the registration lock must leave no folder behind");
    }

    @Test
    public void test_a_project_case_variant_created_concurrently_is_refused() throws Exception {
        holdNameLock(Globals.ADMIN_PROJECT_NAME, Globals.PROJECT_NAMES_LOCK);
        final var raced = new AtomicReference<OperationResponse>();
        final var racer = startParked(() -> createProject("raceddb"), raced);

        assertEquals(OperationStatus.OK, holder.run(() -> createProject("RacedDb")).getStatus());
        holder.release();
        racer.join(LONG_BUDGET_MS);

        assertEquals(ErrorCode.NAME_COLLIDES_ON_DISK.getCode(), raced.get().getErrorCode(),
                "the create that waited must see the variant registered while it waited");
        assertNotNull(cache.getAdminProjectEntry("RacedDb"));
        assertNull(cache.getAdminProjectEntry("raceddb"));
    }

    @Test
    public void test_a_project_registration_lock_timeout_answers_409_5() throws Exception {
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", SHORT_BUDGET_MS);
        holdNameLock(Globals.ADMIN_PROJECT_NAME, Globals.PROJECT_NAMES_LOCK);

        final var response = createProject("timeoutdb");

        assertEquals(ErrorCode.TRANSACTION_LOCK_TIMEOUT.getCode(), response.getErrorCode());
        assertNull(cache.getAdminProjectEntry("timeoutdb"));
        assertFalse(new File(TestGlobals.PATH + File.separator + "timeoutdb").exists(),
                "a create that never got the registration lock must leave no folder behind");
    }

    @Test
    public void test_a_case_variant_create_waits_for_the_drop_to_finish() throws Exception {
        final var createdDuringDrop = new AtomicBoolean();
        final var create = new AtomicReference<CompletableFuture<OperationResponse>>();
        try (var admin = mockStatic(AdminOperationHelper.class, CALLS_REAL_METHODS)) {
            admin.when(() -> AdminOperationHelper.deletePageCollections(eq(TestGlobals.PROJECT), eq(TestGlobals.COLL)))
                    .thenAnswer(invocation -> {
                        create.set(CompletableFuture.supplyAsync(() -> createCollection(CASE_VARIANT), other));
                        Thread.sleep(300);
                        createdDuringDrop.set(create.get().isDone());
                        return invocation.callRealMethod();
                    });

            assertEquals(OperationStatus.OK, dropCollection().getStatus());
        }

        assertFalse(createdDuringDrop.get(),
                "a case-variant create landing between the unregistration and the page-row drop shares its folder");
        assertEquals(OperationStatus.OK, create.get().get(10, TimeUnit.SECONDS).getStatus());
        assertNotNull(cache.getAdminCollectionEntry(TestGlobals.PROJECT, CASE_VARIANT));
        assertNull(cache.getAdminCollectionEntry(TestGlobals.PROJECT, TestGlobals.COLL));
    }

    @Test
    public void test_a_drop_that_cannot_take_the_names_lock_answers_409_5_and_deletes_nothing() throws Exception {
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", SHORT_BUDGET_MS);
        holdNameLock(TestGlobals.PROJECT, Globals.COLLECTION_NAMES_LOCK);

        final var response = dropCollection();
        holder.release();

        assertEquals(ErrorCode.TRANSACTION_LOCK_TIMEOUT.getCode(), response.getErrorCode());
        assertNotNull(cache.getAdminCollectionEntry(TestGlobals.PROJECT, TestGlobals.COLL));
        assertNotNull(cache.getPkIndexAndLoadIfNecessary(TestGlobals.PROJECT, TestGlobals.COLL));
    }

    @Test
    public void test_a_drop_releases_the_names_lock() throws Exception {
        assertEquals(OperationStatus.OK, dropCollection().getStatus());

        assertTrue(namesLockFreeElsewhere());
    }

    @Test
    public void test_a_drop_of_an_unregistered_collection_releases_the_names_lock() throws Exception {
        final var response = CollectionOperationHelper.processDropCollectionOperation(
                new DropCollectionRequest(TestGlobals.PROJECT, "neverCreated"), new RestampedRecords());

        assertEquals(ErrorCode.ERROR_DROPPING_COLLECTION.getCode(), response.getErrorCode());
        assertTrue(namesLockFreeElsewhere());
    }
}
