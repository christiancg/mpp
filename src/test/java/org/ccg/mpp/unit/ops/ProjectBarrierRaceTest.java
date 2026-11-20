package org.ccg.mpp.unit.ops;

import static org.ccg.mpp.test.LockHolder.startParked;
import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.ccg.mpp.bckg_ops.ScheduleRegistry;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.admin.AdminCollEntry;
import org.ccg.mpp.data.admin.AdminProjectEntry;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.ProcedureOperationHelper;
import org.ccg.mpp.ops.ScheduleOperationHelper;
import org.ccg.mpp.ops.admin.ProjectOperationHelper;
import org.ccg.mpp.ops.admin.RestampedRecords;
import org.ccg.mpp.ops.req.CreateCollectionRequest;
import org.ccg.mpp.ops.req.CreateProjectRequest;
import org.ccg.mpp.ops.req.DropProjectRequest;
import org.ccg.mpp.ops.req.SaveProcedureRequest;
import org.ccg.mpp.ops.req.SaveScheduleRequest;
import org.ccg.mpp.ops.req.SetProjectOwnersRequest;
import org.ccg.mpp.ops.resp.OperationResponse;
import org.ccg.mpp.test.LockHolder;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.ccg.mpp.utils.ReflectionUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ProjectBarrierRaceTest {
    private static final String ACTOR = "alice";
    private static final String PROCEDURE = "rollup";
    private static final String RACED_PROJECT = "raced_project";
    private static final String LATE_COLL = "lateColl";
    private static final long DROP_BUDGET_MS = 1000L;
    private static final long SHORT_BUDGET_MS = 100L;
    private static final long LONG_BUDGET_MS = 10_000L;
    private static final Configuration configuration = Configuration.getInstance();
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final ScheduleRegistry registry = IocContainer.get(ScheduleRegistry.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final LockHolder holder = new LockHolder();
    private final long originalTimeout = configuration.getTransactionLockTimeoutMs();

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        TestUtils.setPrivateField(configuration, "schedulesEnabled", true);
        TestUtils.setPrivateField(configuration, "scriptMaxSourceBytes", 262_144L);
        TestUtils.setPrivateField(configuration, "scheduleMaxPerProject", 100);
        TestUtils.setPrivateField(configuration, "scriptTimeZone", "UTC");
        registry.clear();
        assertEquals(OperationStatus.OK, ProcedureOperationHelper
                .executeSave(new SaveProcedureRequest(TestGlobals.PROJECT, PROCEDURE, "return 1;"), ACTOR).getStatus());
    }

    @AfterEach
    public void tearDown() throws Exception {
        holder.close();
        registry.clear();
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", originalTimeout);
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        TestUtils.setPrivateField(configuration, "schedulesEnabled", false);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void holdBarrier(String projectName) throws Exception {
        holder.hold(() -> locks.tryLockProjectExclusive(projectName, LONG_BUDGET_MS),
                () -> locks.releaseProjectExclusive(projectName));
    }

    private void dropTestProjectWhileHoldingTheBarrier() throws Exception {
        final var dropped = holder.run(() -> ProjectOperationHelper
                .processDropProjectOperation(new DropProjectRequest(TestGlobals.PROJECT), new RestampedRecords()));
        assertEquals(OperationStatus.OK, dropped.getStatus());
    }

    private static File metadataFolder(String folderName) {
        return new File(
                TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.PROJECT + Globals.FILE_SEPARATOR + folderName);
    }

    private static SaveScheduleRequest scheduleRequest() {
        final var request = new SaveScheduleRequest(TestGlobals.PROJECT, "nightly", PROCEDURE);
        request.setIntervalMs(2000L);
        return request;
    }

    @Test
    public void test_an_owner_change_waiting_on_a_drop_does_not_resurrect_the_project() throws Exception {
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", LONG_BUDGET_MS);
        holdBarrier(TestGlobals.PROJECT);
        final var result = new AtomicReference<OperationResponse>();
        final var owners = new SetProjectOwnersRequest(TestGlobals.PROJECT);
        owners.setOwners(List.of("mallory"));
        final var setter = startParked(() -> ProjectOperationHelper.processSetProjectOwners(owners), result);

        dropTestProjectWhileHoldingTheBarrier();
        holder.release();
        setter.join(LONG_BUDGET_MS);

        assertEquals(ErrorCode.PROJECT_NOT_FOUND.getCode(), result.get().getErrorCode());
        assertNull(cache.getAdminProjectEntry(TestGlobals.PROJECT));
        assertNull(cache.getPkIndexAdminProjectEntry(TestGlobals.PROJECT));
    }

    @Test
    public void test_a_procedure_save_waits_for_the_project_barrier() throws Exception {
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", SHORT_BUDGET_MS);
        holdBarrier(TestGlobals.PROJECT);

        final var response = ProcedureOperationHelper
                .executeSave(new SaveProcedureRequest(TestGlobals.PROJECT, "p", "return 2;"), ACTOR);

        assertEquals(ErrorCode.TRANSACTION_LOCK_TIMEOUT.getCode(), response.getErrorCode());
    }

    @Test
    public void test_a_procedure_save_after_the_project_was_dropped_writes_nothing() throws Exception {
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", LONG_BUDGET_MS);
        holdBarrier(TestGlobals.PROJECT);
        final var result = new AtomicReference<OperationResponse>();
        final var saver = startParked(() -> ProcedureOperationHelper
                .executeSave(new SaveProcedureRequest(TestGlobals.PROJECT, "p", "return 2;"), ACTOR), result);

        dropTestProjectWhileHoldingTheBarrier();
        holder.release();
        saver.join(LONG_BUDGET_MS);

        assertEquals(ErrorCode.PROJECT_NOT_FOUND.getCode(), result.get().getErrorCode());
        assertFalse(metadataFolder(Globals.PROCEDURES_FOLDER).exists());
        assertNull(cache.getProcedure(TestGlobals.PROJECT, "p"));
    }

    @Test
    public void test_a_schedule_save_waits_for_the_project_barrier() throws Exception {
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", SHORT_BUDGET_MS);
        holdBarrier(TestGlobals.PROJECT);

        final var response = ScheduleOperationHelper.executeSave(scheduleRequest(), ACTOR);

        assertEquals(ErrorCode.TRANSACTION_LOCK_TIMEOUT.getCode(), response.getErrorCode());
    }

    @Test
    public void test_a_schedule_save_after_the_project_was_dropped_registers_nothing() throws Exception {
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", LONG_BUDGET_MS);
        holdBarrier(TestGlobals.PROJECT);
        final var result = new AtomicReference<OperationResponse>();
        final var saver = startParked(() -> ScheduleOperationHelper.executeSave(scheduleRequest(), ACTOR), result);

        dropTestProjectWhileHoldingTheBarrier();
        holder.release();
        saver.join(LONG_BUDGET_MS);

        assertEquals(ErrorCode.PROJECT_NOT_FOUND.getCode(), result.get().getErrorCode());
        assertFalse(metadataFolder(Globals.SCHEDULES_FOLDER).exists());
        assertNull(registry.get(TestGlobals.PROJECT, "nightly"));
    }

    @Test
    public void test_a_duplicate_create_waiting_on_the_barrier_sees_the_first_entry() throws Exception {
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", LONG_BUDGET_MS);
        holdBarrier(RACED_PROJECT);
        final var result = new AtomicReference<OperationResponse>();
        final var creator = startParked(() -> ProjectOperationHelper
                .processCreateProjectOperation(new CreateProjectRequest(RACED_PROJECT), null), result);

        holder.run(() -> {
            AdminOperationHelper
                    .saveProjectEntry(new AdminProjectEntry(RACED_PROJECT, new ArrayList<>(), List.of("first")));
            return null;
        });
        holder.release();
        creator.join(LONG_BUDGET_MS);

        assertEquals(ErrorCode.PROJECT_ALREADY_EXISTS.getCode(), result.get().getErrorCode());
        assertEquals(List.of("first"), cache.getAdminProjectEntry(RACED_PROJECT).getOwners());
    }

    @Test
    public void test_create_project_times_out_on_a_held_barrier() throws Exception {
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", SHORT_BUDGET_MS);
        holdBarrier(RACED_PROJECT);

        final var response = ProjectOperationHelper
                .processCreateProjectOperation(new CreateProjectRequest(RACED_PROJECT), null);

        assertEquals(ErrorCode.TRANSACTION_LOCK_TIMEOUT.getCode(), response.getErrorCode());
        assertNull(cache.getAdminProjectEntry(RACED_PROJECT));
    }

    @Test
    public void test_create_project_releases_the_barrier() throws Exception {
        final var response = ProjectOperationHelper
                .processCreateProjectOperation(new CreateProjectRequest(RACED_PROJECT), null);

        assertEquals(OperationStatus.OK, response.getStatus());
        holdBarrier(RACED_PROJECT);
    }

    @Test
    public void test_create_collection_times_out_while_a_drop_holds_the_project() throws Exception {
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", DROP_BUDGET_MS);
        holdBarrier(TestGlobals.PROJECT);

        final var response = processor.processMessage(new CreateCollectionRequest(TestGlobals.PROJECT, LATE_COLL));

        assertEquals("409-5", response.getErrorCode());
        assertNull(cache.getAdminCollectionEntry(TestGlobals.PROJECT, LATE_COLL));
    }

    @Test
    public void test_drop_gives_up_on_a_held_collection_lock_and_releases_everything() throws Exception {
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", DROP_BUDGET_MS);
        holder.hold(() -> locks.tryLockWrite(TestGlobals.PROJECT, TestGlobals.COLL),
                () -> locks.release(TestGlobals.PROJECT, TestGlobals.COLL));

        final var response = dropAsync().get(10, TimeUnit.SECONDS);

        assertEquals("409-5", response.getErrorCode());
        assertNotNull(cache.getAdminProjectEntry(TestGlobals.PROJECT), "a drop that gave up must leave the project");
        assertTrue(locks.tryLockProjectExclusive(TestGlobals.PROJECT, 0), "the drop must have released the barrier");
        locks.releaseProjectExclusive(TestGlobals.PROJECT);
    }

    @Test
    public void test_drop_locks_a_collection_registered_while_it_waited_for_the_project() throws Exception {
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", DROP_BUDGET_MS);
        assertTrue(holder.run(() -> locks.tryLockProjectShared(TestGlobals.PROJECT, DROP_BUDGET_MS)));
        final var drop = dropAsync();
        awaitQueuedDrop();
        holder.hold(() -> {
            AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.PROJECT, LATE_COLL));
            IocContainer.get(FileSystem.class).createCollectionFile(TestGlobals.PROJECT, LATE_COLL);
            final var locked = locks.tryLockWrite(TestGlobals.PROJECT, LATE_COLL, DROP_BUDGET_MS);
            locks.releaseProjectShared(TestGlobals.PROJECT);
            return locked;
        }, () -> locks.release(TestGlobals.PROJECT, LATE_COLL));

        final var response = drop.get(10, TimeUnit.SECONDS);

        assertEquals("409-5", response.getErrorCode(),
                "the drop must wait on the late collection's lock instead of deleting it unlocked");
        assertNotNull(cache.getAdminCollectionEntry(TestGlobals.PROJECT, LATE_COLL));
        holder.release();
        assertEquals(OperationStatus.OK,
                processor.processMessage(new DropProjectRequest(TestGlobals.PROJECT)).getStatus());
    }

    private CompletableFuture<OperationResponse> dropAsync() {
        return CompletableFuture
                .supplyAsync(() -> processor.processMessage(new DropProjectRequest(TestGlobals.PROJECT)));
    }

    private void awaitQueuedDrop() throws Exception {
        final var type = new ReflectionUtils.TypeToken<Map<String, ReentrantReadWriteLock>>() {
        };
        final var barrier = TestUtils.getPrivateField(locks, "locks", type).get(TestGlobals.PROJECT);
        final var deadline = System.currentTimeMillis() + 5000;
        while (!barrier.hasQueuedThreads()) {
            assertTrue(System.currentTimeMillis() < deadline);
            Thread.onSpinWait();
        }
    }
}
