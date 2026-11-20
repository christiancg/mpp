package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import org.ccg.mpp.bckg_ops.ScheduleRegistry;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.ejson.EJson;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.fs.io.OwnerOnlyFiles;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.ProcedureCallHelper;
import org.ccg.mpp.ops.ProcedureOperationHelper;
import org.ccg.mpp.ops.ScheduleOperationHelper;
import org.ccg.mpp.ops.TriggerOperationHelper;
import org.ccg.mpp.ops.UserOperationHelper;
import org.ccg.mpp.ops.req.CallProcedureRequest;
import org.ccg.mpp.ops.req.CreateUserRequest;
import org.ccg.mpp.ops.req.DeleteProcedureRequest;
import org.ccg.mpp.ops.req.DeleteScheduleRequest;
import org.ccg.mpp.ops.req.DeleteSchemaRequest;
import org.ccg.mpp.ops.req.DeleteTriggerRequest;
import org.ccg.mpp.ops.req.ListProceduresRequest;
import org.ccg.mpp.ops.req.ListSchedulesRequest;
import org.ccg.mpp.ops.req.ListTriggersRequest;
import org.ccg.mpp.ops.req.SaveProcedureRequest;
import org.ccg.mpp.ops.req.SaveScheduleRequest;
import org.ccg.mpp.ops.req.SaveSchemaRequest;
import org.ccg.mpp.ops.req.SaveTriggerRequest;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class MetadataDeleteFailureTest {
    private static final String ADMIN = "unreadableadmin";
    private static final String UNPARSEABLE = "{not json";
    private static final Configuration configuration = Configuration.getInstance();
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final EJson eJson = IocContainer.get(EJson.class);
    private File lockedFolder;

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
        final var request = new CreateUserRequest();
        request.setUsername(ADMIN);
        request.setPassword("password123");
        request.setAdmin(true);
        request.setGlobalPermissions(new HashSet<>());
        request.setProjectPermissions(new HashMap<>());
        request.setCollectionPermissions(new HashMap<>());
        UserOperationHelper.processCreateUser(request);
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
        TestUtils.setPrivateField(configuration, "scheduleMaxPerProject", 100);
        TestUtils.setPrivateField(configuration, "scriptTimeZone", "UTC");
        for (final var name : fs.metadataFiles().listProcedureNames(TestGlobals.PROJECT)) {
            fs.metadataFiles().deleteProcedure(TestGlobals.PROJECT, name);
        }
        for (final var name : fs.metadataFiles().listScheduleNames(TestGlobals.PROJECT)) {
            fs.metadataFiles().deleteSchedule(TestGlobals.PROJECT, name);
        }
        fs.metadataFiles().deleteTriggers(TestGlobals.PROJECT, TestGlobals.COLL);
        cache.removeProceduresForProject(TestGlobals.PROJECT);
        cache.removeSchedulesForProject(TestGlobals.PROJECT);
        cache.removeTriggers(TestGlobals.PROJECT, TestGlobals.COLL);
        assertEquals(OperationStatus.OK, processor
                .processMessage(new SaveProcedureRequest(TestGlobals.PROJECT, "kept", "return 1;")).getStatus());
    }

    @AfterEach
    void unlock() throws Exception {
        if (lockedFolder != null) {
            Files.setPosixFilePermissions(lockedFolder.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"));
            lockedFolder = null;
        }
    }

    private void lock(String relativeFolder) throws Exception {
        assumeTrue(OwnerOnlyFiles.supported(), "only a POSIX file system has permission bits that deny its owner");
        lockedFolder = new File(TestUtils.getDbPath(fs), TestGlobals.PROJECT + File.separator + relativeFolder);
        Files.setPosixFilePermissions(lockedFolder.toPath(), PosixFilePermissions.fromString("r-xr-xr-x"));
        assumeFalse(Files.isWritable(lockedFolder.toPath()), "a superuser ignores the permission bits");
    }

    @Test
    public void test_a_procedure_whose_file_cannot_be_removed_answers_an_error_and_stays() throws Exception {
        assertEquals(OperationStatus.OK,
                processor.processMessage(new SaveProcedureRequest(TestGlobals.PROJECT, "unreferenced", "return 1;"))
                        .getStatus());
        lock(Globals.PROCEDURES_FOLDER);

        final var response = processor.processMessage(new DeleteProcedureRequest(TestGlobals.PROJECT, "unreferenced"));

        assertEquals(ErrorCode.ERROR_DELETING_PROCEDURE.getCode(), response.getErrorCode(), response.getMessage());
        assertNotNull(cache.getProcedure(TestGlobals.PROJECT, "unreferenced"));
    }

    @Test
    public void test_a_schedule_whose_file_cannot_be_removed_answers_an_error_and_stays_scheduled() throws Exception {
        final var save = new SaveScheduleRequest(TestGlobals.PROJECT, "nightly", "kept");
        save.setIntervalMs(60_000L);
        assertEquals(OperationStatus.OK, processor.processMessage(save).getStatus());
        lock(Globals.SCHEDULES_FOLDER);

        final var response = processor.processMessage(new DeleteScheduleRequest(TestGlobals.PROJECT, "nightly"));

        assertEquals(ErrorCode.ERROR_DELETING_SCHEDULE.getCode(), response.getErrorCode(), response.getMessage());
        assertNotNull(cache.getSchedule(TestGlobals.PROJECT, "nightly"));
        assertNotNull(IocContainer.get(ScheduleRegistry.class).get(TestGlobals.PROJECT, "nightly"));
    }

    @Test
    public void test_a_schema_whose_file_cannot_be_removed_answers_an_error_and_stays() throws Exception {
        assertEquals(OperationStatus.OK, processor.processMessage(new SaveSchemaRequest(TestGlobals.PROJECT,
                TestGlobals.COLL, eJson.fromJson("{\"type\":\"object\"}", JsonObject.class))).getStatus());
        lock(TestGlobals.COLL);

        final var response = processor.processMessage(new DeleteSchemaRequest(TestGlobals.PROJECT, TestGlobals.COLL));

        assertEquals(ErrorCode.ERROR_DELETING_SCHEMA.getCode(), response.getErrorCode(), response.getMessage());
        assertNotNull(cache.getCollectionSchema(TestGlobals.PROJECT, TestGlobals.COLL));
    }

    @Test
    public void test_the_last_trigger_whose_file_cannot_be_removed_answers_an_error_and_stays() throws Exception {
        assertEquals(OperationStatus.OK, processor.processMessage(
                new SaveTriggerRequest(TestGlobals.PROJECT, TestGlobals.COLL, "onInsert", List.of("CREATED"), "kept"))
                .getStatus());
        lock(TestGlobals.COLL);

        final var response = processor
                .processMessage(new DeleteTriggerRequest(TestGlobals.PROJECT, TestGlobals.COLL, "onInsert"));

        assertEquals(ErrorCode.ERROR_DELETING_TRIGGER.getCode(), response.getErrorCode(), response.getMessage());
        assertFalse(cache.getTriggersFor(TestGlobals.PROJECT, TestGlobals.COLL).isEmpty());
    }

    @Test
    public void test_listing_procedures_answers_an_error_when_a_definition_is_unparseable() throws Exception {
        fs.metadataFiles().writeProcedure(TestGlobals.PROJECT, "broken", UNPARSEABLE);

        final var response = ProcedureOperationHelper.executeList(new ListProceduresRequest(TestGlobals.PROJECT), null);

        assertEquals(ErrorCode.ERROR_RETRIEVING.getCode(), response.getErrorCode());
    }

    @Test
    public void test_listing_triggers_answers_an_error_when_a_trigger_file_is_unparseable() throws Exception {
        fs.metadataFiles().writeTriggers(TestGlobals.PROJECT, TestGlobals.COLL, UNPARSEABLE);
        cache.removeTriggers(TestGlobals.PROJECT, TestGlobals.COLL);

        final var response = TriggerOperationHelper
                .executeList(new ListTriggersRequest(TestGlobals.PROJECT, TestGlobals.COLL));

        assertEquals(ErrorCode.ERROR_RETRIEVING.getCode(), response.getErrorCode());
    }

    @Test
    public void test_listing_schedules_answers_an_error_when_a_definition_is_unparseable() throws Exception {
        fs.metadataFiles().writeSchedule(TestGlobals.PROJECT, "broken", UNPARSEABLE);

        final var response = ScheduleOperationHelper.executeList(new ListSchedulesRequest(TestGlobals.PROJECT));

        assertEquals(ErrorCode.ERROR_RETRIEVING.getCode(), response.getErrorCode());
    }

    @Test
    public void test_calling_a_procedure_answers_an_error_when_its_definition_is_unparseable() throws Exception {
        fs.metadataFiles().writeProcedure(TestGlobals.PROJECT, "broken", UNPARSEABLE);
        final var request = new CallProcedureRequest(TestGlobals.PROJECT, "broken", new JsonObject());

        final var response = ProcedureCallHelper.execute(request, ADMIN, null);

        assertEquals(ErrorCode.ERROR_RETRIEVING.getCode(), response.getErrorCode());
    }
}
