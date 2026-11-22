package org.ccg.mpp.unit.ops.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import org.ccg.mpp.data.admin.AdminUserEntry;
import org.ccg.mpp.data.auth.PermissionLevel;
import org.ccg.mpp.data.auth.ScriptPermissionLevel;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.auth.AuthorizationChecker;
import org.ccg.mpp.ops.req.CallProcedureRequest;
import org.ccg.mpp.ops.req.DeleteEntrypointRequest;
import org.ccg.mpp.ops.req.DeleteProcedureRequest;
import org.ccg.mpp.ops.req.DeleteScheduleRequest;
import org.ccg.mpp.ops.req.DeleteTriggerRequest;
import org.ccg.mpp.ops.req.ListEntrypointsRequest;
import org.ccg.mpp.ops.req.ListProceduresRequest;
import org.ccg.mpp.ops.req.ListSchedulesRequest;
import org.ccg.mpp.ops.req.ListTriggersRequest;
import org.ccg.mpp.ops.req.OperationRequest;
import org.ccg.mpp.ops.req.SaveEntrypointRequest;
import org.ccg.mpp.ops.req.SaveProcedureRequest;
import org.ccg.mpp.ops.req.SaveScheduleRequest;
import org.ccg.mpp.ops.req.SaveTriggerRequest;
import org.ccg.mpp.ops.req.TestTriggerRequest;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class AuthorizationCheckerProcedureTest {
    private static final String OWNER = "dbowner";

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
        AdminOperationHelper.updateProjectOwners(TestGlobals.PROJECT, List.of(OWNER));
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static AdminUserEntry admin() {
        return new AdminUserEntry("admin", "hash", true, new HashSet<>(), new HashMap<>(), new HashMap<>());
    }

    private static AdminUserEntry owner() {
        return new AdminUserEntry(OWNER, "hash", false, new HashSet<>(), new HashMap<>(), new HashMap<>());
    }

    private static AdminUserEntry userWith(ScriptPermissionLevel level, PermissionLevel projectLevel) {
        final var scriptPerms = new HashMap<String, ScriptPermissionLevel>();
        if (level != null) {
            scriptPerms.put(TestGlobals.PROJECT, level);
        }
        final var projectPerms = new HashMap<String, PermissionLevel>();
        if (projectLevel != null) {
            projectPerms.put(TestGlobals.PROJECT, projectLevel);
        }
        return new AdminUserEntry("user", "hash", false, new HashSet<>(), projectPerms, new HashMap<>(), scriptPerms);
    }

    private static SaveProcedureRequest saveProcedure() {
        return new SaveProcedureRequest(TestGlobals.PROJECT, "p", "return 1;");
    }

    private static SaveTriggerRequest saveTrigger() {
        return new SaveTriggerRequest(TestGlobals.PROJECT, TestGlobals.COLL, "t", List.of("CREATED"), "p");
    }

    private static SaveScheduleRequest saveSchedule() {
        final var request = new SaveScheduleRequest(TestGlobals.PROJECT, "sch", "p");
        request.setIntervalMs(1000L);
        return request;
    }

    private static TestTriggerRequest testTrigger() {
        return new TestTriggerRequest(TestGlobals.PROJECT, TestGlobals.COLL, "t", "CREATED", new JsonObject());
    }

    private static List<OperationRequest> managementRequests() {
        return List.of(saveProcedure(), new DeleteProcedureRequest(TestGlobals.PROJECT, "p"), saveTrigger(),
                new DeleteTriggerRequest(TestGlobals.PROJECT, TestGlobals.COLL, "t"), saveSchedule(),
                new DeleteScheduleRequest(TestGlobals.PROJECT, "sch"), testTrigger());
    }

    @Test
    public void test_admin_may_save_procedure() {
        for (final var request : managementRequests()) {
            assertTrue(AuthorizationChecker.check(request, admin()).isAllowed(), request.getType().name());
        }
    }

    @Test
    public void test_owner_may_save_procedure() {
        for (final var request : managementRequests()) {
            assertTrue(AuthorizationChecker.check(request, owner()).isAllowed(), request.getType().name());
        }
    }

    // The privilege-laundering guard: whoever installs a procedure hands higher-privileged callers code to
    // execute, and a trigger runs with the installer's authority. Neither may follow from READ_WRITE.
    @Test
    public void test_read_write_user_may_not_save_procedure() {
        final var writer = userWith(null, PermissionLevel.READ_WRITE);
        for (final var request : managementRequests()) {
            assertFalse(AuthorizationChecker.check(request, writer).isAllowed(), request.getType().name());
        }
    }

    @Test
    public void test_run_level_allows_call_but_not_save() {
        final var runner = userWith(ScriptPermissionLevel.RUN, null);
        assertTrue(AuthorizationChecker.check(new CallProcedureRequest(TestGlobals.PROJECT, "p", null), runner)
                .isAllowed());
        for (final var request : managementRequests()) {
            assertFalse(AuthorizationChecker.check(request, runner).isAllowed(), request.getType().name());
        }
    }

    @Test
    public void test_manage_level_allows_save_and_call() {
        final var manager = userWith(ScriptPermissionLevel.MANAGE, PermissionLevel.READ_WRITE);
        assertTrue(AuthorizationChecker.check(new CallProcedureRequest(TestGlobals.PROJECT, "p", null), manager)
                .isAllowed());
        for (final var request : managementRequests()) {
            assertTrue(AuthorizationChecker.check(request, manager).isAllowed(), request.getType().name());
        }
    }

    @Test
    public void test_none_level_allows_nothing() {
        final var denied = userWith(ScriptPermissionLevel.NONE, null);
        assertFalse(AuthorizationChecker.check(new CallProcedureRequest(TestGlobals.PROJECT, "p", null), denied)
                .isAllowed());
        for (final var request : managementRequests()) {
            assertFalse(AuthorizationChecker.check(request, denied).isAllowed(), request.getType().name());
        }
    }

    @Test
    public void test_manage_on_one_project_does_not_leak_to_another() {
        final var scriptPerms = new HashMap<String, ScriptPermissionLevel>();
        scriptPerms.put(TestGlobals.PROJECT, ScriptPermissionLevel.MANAGE);
        final var projectPerms = new HashMap<String, PermissionLevel>();
        projectPerms.put("otherProject", PermissionLevel.READ_WRITE);
        final var user = new AdminUserEntry("user", "hash", false, new HashSet<>(), projectPerms, new HashMap<>(),
                scriptPerms);
        assertFalse(AuthorizationChecker.check(new SaveProcedureRequest("otherProject", "p", "return 1;"), user)
                .isAllowed());
        assertFalse(AuthorizationChecker.check(new CallProcedureRequest("otherProject", "p", null), user).isAllowed());
    }

    // The ordering trap: these ops must be denied AFTER the ownership short-circuit, never by being listed
    // in ADMIN_ONLY_OPERATIONS - which is tested first and would lock out project owners.
    @Test
    public void test_procedure_ddl_is_not_admin_only_so_owners_are_not_locked_out() {
        for (final var request : managementRequests()) {
            assertTrue(AuthorizationChecker.check(request, owner()).isAllowed(),
                    request.getType() + " must stay reachable for a project owner");
        }
    }

    @Test
    public void test_list_requires_read_only() {
        final var reader = userWith(null, PermissionLevel.READ);
        assertTrue(AuthorizationChecker.check(new ListProceduresRequest(TestGlobals.PROJECT), reader).isAllowed());
        assertTrue(AuthorizationChecker.check(new ListTriggersRequest(TestGlobals.PROJECT, TestGlobals.COLL), reader)
                .isAllowed());
        assertTrue(AuthorizationChecker.check(new ListSchedulesRequest(TestGlobals.PROJECT), reader).isAllowed());
        final var nobody = userWith(null, null);
        assertFalse(AuthorizationChecker.check(new ListSchedulesRequest(TestGlobals.PROJECT), nobody).isAllowed());
        assertFalse(AuthorizationChecker.check(new ListProceduresRequest(TestGlobals.PROJECT), nobody).isAllowed());
        assertFalse(AuthorizationChecker.check(new ListTriggersRequest(TestGlobals.PROJECT, TestGlobals.COLL), nobody)
                .isAllowed());
    }

    @Test
    public void test_every_new_operation_type_is_covered_by_a_branch() {
        final var nobody = userWith(null, null);
        for (final var type : List.of(OperationType.SAVE_PROCEDURE, OperationType.DELETE_PROCEDURE,
                OperationType.LIST_PROCEDURES, OperationType.CALL_PROCEDURE, OperationType.SAVE_TRIGGER,
                OperationType.DELETE_TRIGGER, OperationType.LIST_TRIGGERS, OperationType.SAVE_SCHEDULE,
                OperationType.DELETE_SCHEDULE, OperationType.LIST_SCHEDULES, OperationType.TEST_TRIGGER)) {
            final var request = switch (type) {
                case SAVE_PROCEDURE -> saveProcedure();
                case DELETE_PROCEDURE -> new DeleteProcedureRequest(TestGlobals.PROJECT, "p");
                case LIST_PROCEDURES -> new ListProceduresRequest(TestGlobals.PROJECT);
                case CALL_PROCEDURE -> new CallProcedureRequest(TestGlobals.PROJECT, "p", null);
                case SAVE_TRIGGER -> saveTrigger();
                case DELETE_TRIGGER -> new DeleteTriggerRequest(TestGlobals.PROJECT, TestGlobals.COLL, "t");
                case SAVE_SCHEDULE -> saveSchedule();
                case DELETE_SCHEDULE -> new DeleteScheduleRequest(TestGlobals.PROJECT, "sch");
                case LIST_SCHEDULES -> new ListSchedulesRequest(TestGlobals.PROJECT);
                case TEST_TRIGGER -> testTrigger();
                default -> new ListTriggersRequest(TestGlobals.PROJECT, TestGlobals.COLL);
            };
            assertFalse(AuthorizationChecker.check(request, nobody).isAllowed(), type.name());
            assertTrue(AuthorizationChecker.check(request, admin()).isAllowed(), type.name());
        }
    }

    @Test
    public void test_entrypoint_management_needs_manage_and_listing_needs_read() {
        final var save = new SaveEntrypointRequest(TestGlobals.PROJECT, "e", "GET", "/x");
        save.setProcedureName("p");
        final var delete = new DeleteEntrypointRequest(TestGlobals.PROJECT, "e");
        final var list = new ListEntrypointsRequest(TestGlobals.PROJECT);
        final var runner = userWith(ScriptPermissionLevel.RUN, PermissionLevel.READ_WRITE);
        final var manager = userWith(ScriptPermissionLevel.MANAGE, null);
        final var reader = userWith(null, PermissionLevel.READ);
        final var nobody = userWith(null, null);

        assertFalse(AuthorizationChecker.check(save, runner).isAllowed());
        assertFalse(AuthorizationChecker.check(delete, runner).isAllowed());
        assertTrue(AuthorizationChecker.check(save, manager).isAllowed());
        assertTrue(AuthorizationChecker.check(delete, manager).isAllowed());
        assertTrue(AuthorizationChecker.check(save, owner()).isAllowed());
        assertTrue(AuthorizationChecker.check(list, reader).isAllowed());
        assertFalse(AuthorizationChecker.check(list, nobody).isAllowed());
    }
}
