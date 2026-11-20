package org.ccg.mpp.unit.ops;

import static org.ccg.mpp.test.LockHolder.startParked;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.ccg.mpp.bckg_ops.ScheduleRegistry;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.ProcedureOperationHelper;
import org.ccg.mpp.ops.ScheduleOperationHelper;
import org.ccg.mpp.ops.TriggerOperationHelper;
import org.ccg.mpp.ops.req.DeleteProcedureRequest;
import org.ccg.mpp.ops.req.SaveProcedureRequest;
import org.ccg.mpp.ops.req.SaveScheduleRequest;
import org.ccg.mpp.ops.req.SaveTriggerRequest;
import org.ccg.mpp.ops.resp.OperationResponse;
import org.ccg.mpp.test.LockHolder;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ProcedureReferenceRaceTest {
    private static final String ACTOR = "alice";
    private static final String PROCEDURE = "recalc";
    private static final long BUDGET_MS = 10_000L;
    private static final Configuration configuration = Configuration.getInstance();
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final ScheduleRegistry registry = IocContainer.get(ScheduleRegistry.class);
    private final LockHolder holder = new LockHolder();

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        TestUtils.setPrivateField(configuration, "schedulesEnabled", true);
        TestUtils.setPrivateField(configuration, "scriptMaxSourceBytes", 262_144L);
        TestUtils.setPrivateField(configuration, "scheduleMaxPerProject", 100);
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", BUDGET_MS);
        registry.clear();
        assertEquals(OperationStatus.OK, ProcedureOperationHelper
                .executeSave(new SaveProcedureRequest(TestGlobals.PROJECT, PROCEDURE, "return 1;"), ACTOR).getStatus());
    }

    @AfterEach
    public void tearDown() throws Exception {
        holder.close();
        registry.clear();
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        TestUtils.setPrivateField(configuration, "schedulesEnabled", false);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @Test
    public void test_a_trigger_save_waiting_on_a_procedure_delete_refuses_the_dangling_reference() throws Exception {
        holdProceduresLock();
        final var result = new AtomicReference<OperationResponse>();
        final var saver = startParked(() -> TriggerOperationHelper.executeSave(
                new SaveTriggerRequest(TestGlobals.PROJECT, TestGlobals.COLL, "audit", List.of("CREATED"), PROCEDURE),
                ACTOR), result);

        deleteProcedureWhileHoldingTheLock();
        holder.release();
        saver.join(BUDGET_MS);

        assertEquals(ErrorCode.PROCEDURE_NOT_FOUND.getCode(), result.get().getErrorCode());
        assertTrue(cache.getTriggersFor(TestGlobals.PROJECT, TestGlobals.COLL).isEmpty());
    }

    @Test
    public void test_a_schedule_save_waiting_on_a_procedure_delete_refuses_the_dangling_reference() throws Exception {
        holdProceduresLock();
        final var result = new AtomicReference<OperationResponse>();
        final var request = new SaveScheduleRequest(TestGlobals.PROJECT, "nightly", PROCEDURE);
        request.setIntervalMs(2000L);
        final var saver = startParked(() -> ScheduleOperationHelper.executeSave(request, ACTOR), result);

        deleteProcedureWhileHoldingTheLock();
        holder.release();
        saver.join(BUDGET_MS);

        assertEquals(ErrorCode.PROCEDURE_NOT_FOUND.getCode(), result.get().getErrorCode());
        assertNull(cache.getSchedule(TestGlobals.PROJECT, "nightly"));
        assertTrue(fs.metadataFiles().listScheduleNames(TestGlobals.PROJECT).isEmpty());
    }

    @Test
    public void test_a_procedure_delete_after_a_trigger_save_is_refused_as_referenced() throws Exception {
        assertEquals(OperationStatus.OK, TriggerOperationHelper.executeSave(
                new SaveTriggerRequest(TestGlobals.PROJECT, TestGlobals.COLL, "audit", List.of("CREATED"), PROCEDURE),
                ACTOR).getStatus());

        final var deleted = ProcedureOperationHelper
                .executeDelete(new DeleteProcedureRequest(TestGlobals.PROJECT, PROCEDURE), ACTOR);

        assertEquals(ErrorCode.INVALID_TRIGGER.getCode(), deleted.getErrorCode());
    }

    @Test
    public void test_a_case_variant_delete_keeps_a_referenced_procedure() throws Exception {
        assertEquals(OperationStatus.OK, TriggerOperationHelper.executeSave(
                new SaveTriggerRequest(TestGlobals.PROJECT, TestGlobals.COLL, "audit", List.of("CREATED"), PROCEDURE),
                ACTOR).getStatus());

        ProcedureOperationHelper.executeDelete(new DeleteProcedureRequest(TestGlobals.PROJECT, "Recalc"), ACTOR);

        assertNotNull(fs.metadataFiles().readProcedure(TestGlobals.PROJECT, PROCEDURE),
                "the referenced procedure's file is kept");
        assertNotNull(cache.getProcedure(TestGlobals.PROJECT, PROCEDURE));
    }

    @Test
    public void test_a_trigger_cannot_name_a_case_variant_of_a_procedure() throws Exception {
        final var saved = TriggerOperationHelper.executeSave(
                new SaveTriggerRequest(TestGlobals.PROJECT, TestGlobals.COLL, "audit", List.of("CREATED"), "Recalc"),
                ACTOR);

        assertNotEquals(OperationStatus.OK, saved.getStatus());
        assertNull(cache.getProcedure(TestGlobals.PROJECT, "Recalc"));
    }

    private void deleteProcedureWhileHoldingTheLock() throws Exception {
        final var deleted = holder.run(() -> ProcedureOperationHelper
                .executeDelete(new DeleteProcedureRequest(TestGlobals.PROJECT, PROCEDURE), ACTOR));
        assertEquals(OperationStatus.OK, deleted.getStatus());
    }

    private void holdProceduresLock() throws Exception {
        holder.hold(() -> {
            locks.lock(TestGlobals.PROJECT, Globals.PROCEDURES_FOLDER);
            return true;
        }, () -> locks.release(TestGlobals.PROJECT, Globals.PROCEDURES_FOLDER));
    }
}
