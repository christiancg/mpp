package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import org.ccg.mpp.bckg_ops.ScheduleRegistry;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.ClusterAdminHelper;
import org.ccg.mpp.ops.CompiledProcedureCache;
import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.req.CreateProjectRequest;
import org.ccg.mpp.ops.req.DeleteScheduleRequest;
import org.ccg.mpp.ops.req.DropProjectRequest;
import org.ccg.mpp.ops.req.ListSchedulesRequest;
import org.ccg.mpp.ops.req.SaveProcedureRequest;
import org.ccg.mpp.ops.req.SaveScheduleRequest;
import org.ccg.mpp.ops.resp.ListSchedulesResponse;
import org.ccg.mpp.ops.resp.SaveScheduleResponse;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class OperationProcessorScheduleTest {
    private static final Configuration configuration = Configuration.getInstance();
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final ScheduleRegistry registry = IocContainer.get(ScheduleRegistry.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        TestUtils.setPrivateField(configuration, "schedulesEnabled", false);
        IocContainer.get(ScheduleRegistry.class).clear();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void reset() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        TestUtils.setPrivateField(configuration, "schedulesEnabled", true);
        TestUtils.setPrivateField(configuration, "scriptMaxSourceBytes", 262_144L);
        TestUtils.setPrivateField(configuration, "scheduleMaxPerProject", 100);
        TestUtils.setPrivateField(configuration, "scriptTimeZone", "UTC");
        for (final var name : fs.metadataFiles().listScheduleNames(TestGlobals.PROJECT)) {
            fs.metadataFiles().deleteSchedule(TestGlobals.PROJECT, name);
        }
        for (final var name : fs.metadataFiles().listProcedureNames(TestGlobals.PROJECT)) {
            fs.metadataFiles().deleteProcedure(TestGlobals.PROJECT, name);
        }
        cache.removeSchedulesForProject(TestGlobals.PROJECT);
        cache.removeProceduresForProject(TestGlobals.PROJECT);
        cache.removeTriggers(TestGlobals.PROJECT, TestGlobals.COLL);
        IocContainer.get(CompiledProcedureCache.class).invalidateProject(TestGlobals.PROJECT);
        registry.clear();
        processor.processMessage(new SaveProcedureRequest(TestGlobals.PROJECT, "job", "return 1;"));
    }

    private static SaveScheduleRequest saveRequest() {
        final var request = new SaveScheduleRequest(TestGlobals.PROJECT, "nightly", "job");
        request.setIntervalMs(60_000L);
        return request;
    }

    @Test
    public void test_dispatches_save_list_and_delete() {
        assertInstanceOf(SaveScheduleResponse.class, processor.processMessage(saveRequest()));
        final var listed = (ListSchedulesResponse) processor
                .processMessage(new ListSchedulesRequest(TestGlobals.PROJECT));
        assertEquals(1, listed.getSchedules().size());
        assertEquals(OperationStatus.OK,
                processor.processMessage(new DeleteScheduleRequest(TestGlobals.PROJECT, "nightly")).getStatus());
        assertNull(cache.getSchedule(TestGlobals.PROJECT, "nightly"));
    }

    @Test
    public void test_save_failure_is_reported_as_an_error_code() throws Exception {
        TestUtils.setPrivateField(configuration, "schedulesEnabled", false);
        assertEquals(ErrorCode.SCRIPTS_DISABLED.getCode(), processor.processMessage(saveRequest()).getErrorCode());
        assertEquals(ErrorCode.SCRIPTS_DISABLED.getCode(),
                processor.processMessage(new DeleteScheduleRequest(TestGlobals.PROJECT, "nightly")).getErrorCode());
        assertEquals(ErrorCode.SCRIPTS_DISABLED.getCode(),
                processor.processMessage(new ListSchedulesRequest(TestGlobals.PROJECT)).getErrorCode());
    }

    @Test
    public void test_schedule_mutations_are_coordinated_admin_ops() {
        assertTrue(ClusterAdminHelper.isCoordinatedAdminOp(OperationType.SAVE_SCHEDULE));
        assertTrue(ClusterAdminHelper.isCoordinatedAdminOp(OperationType.DELETE_SCHEDULE));
        assertFalse(ClusterAdminHelper.isCoordinatedAdminOp(OperationType.LIST_SCHEDULES));
    }

    @Test
    public void test_drop_project_removes_schedules() throws Exception {
        final var projectName = "scheddropdb";
        assertEquals(OperationStatus.OK, processor.processMessage(new CreateProjectRequest(projectName)).getStatus());
        processor.processMessage(new SaveProcedureRequest(projectName, "job", "return 1;"));
        final var request = new SaveScheduleRequest(projectName, "nightly", "job");
        request.setIntervalMs(60_000L);
        assertInstanceOf(SaveScheduleResponse.class, processor.processMessage(request));

        assertEquals(OperationStatus.OK, processor.processMessage(new DropProjectRequest(projectName)).getStatus());
        assertTrue(fs.metadataFiles().listScheduleNames(projectName).isEmpty());
        assertNull(cache.getSchedule(projectName, "nightly"));
    }
}
