package org.ccg.mpp.unit.ops.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.ccg.mpp.data.admin.AdminUserEntry;
import org.ccg.mpp.data.auth.PermissionLevel;
import org.ccg.mpp.data.auth.ScriptPermissionLevel;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.auth.AuthorizationChecker;
import org.ccg.mpp.ops.req.DeleteProcedureRequest;
import org.ccg.mpp.ops.req.DeleteScheduleRequest;
import org.ccg.mpp.ops.req.DeleteTriggerRequest;
import org.ccg.mpp.ops.req.OperationRequest;
import org.ccg.mpp.ops.req.SaveProcedureRequest;
import org.ccg.mpp.ops.req.SaveScheduleRequest;
import org.ccg.mpp.ops.req.SaveTriggerRequest;
import org.ccg.mpp.ops.req.TestTriggerRequest;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class AuthorizationCheckerTriggerAccessTest {
    private static final String OWNER = "dbowner";
    private static final String COLL_KEY = TestGlobals.PROJECT + "|" + TestGlobals.COLL;

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

    private static AdminUserEntry manager(PermissionLevel projectLevel, PermissionLevel collLevel) {
        final var projectPerms = new HashMap<String, PermissionLevel>();
        if (projectLevel != null) {
            projectPerms.put(TestGlobals.PROJECT, projectLevel);
        }
        final var collPerms = new HashMap<String, PermissionLevel>();
        if (collLevel != null) {
            collPerms.put(COLL_KEY, collLevel);
        }
        return new AdminUserEntry("manager", "hash", false, new HashSet<>(), projectPerms, collPerms,
                new HashMap<>(Map.of(TestGlobals.PROJECT, ScriptPermissionLevel.MANAGE)));
    }

    private static List<OperationRequest> triggerRequests() {
        return List.of(new SaveTriggerRequest(TestGlobals.PROJECT, TestGlobals.COLL, "t", List.of("CREATED"), "p"),
                new DeleteTriggerRequest(TestGlobals.PROJECT, TestGlobals.COLL, "t"),
                new TestTriggerRequest(TestGlobals.PROJECT, TestGlobals.COLL, "t", "CREATED", new JsonObject()));
    }

    private static void assertTriggerAccess(boolean allowed, AdminUserEntry user) {
        for (final var request : triggerRequests()) {
            assertEquals(allowed, AuthorizationChecker.check(request, user).isAllowed(), request.getType().name());
        }
    }

    @Test
    public void test_manage_without_a_data_grant_may_not_manage_triggers() {
        assertTriggerAccess(false, manager(null, null));
    }

    @Test
    public void test_manage_with_project_read_may_not_manage_triggers() {
        assertTriggerAccess(false, manager(PermissionLevel.READ, null));
    }

    @Test
    public void test_manage_with_project_read_write_may_manage_triggers() {
        assertTriggerAccess(true, manager(PermissionLevel.READ_WRITE, null));
    }

    @Test
    public void test_a_collection_read_write_entry_suffices_without_a_project_grant() {
        assertTriggerAccess(true, manager(null, PermissionLevel.READ_WRITE));
    }

    @Test
    public void test_a_collection_read_entry_narrows_a_project_read_write_grant() {
        assertTriggerAccess(false, manager(PermissionLevel.READ_WRITE, PermissionLevel.READ));
    }

    @Test
    public void test_owner_and_admin_need_no_data_grant() {
        final var owner = new AdminUserEntry(OWNER, "hash", false, new HashSet<>(), new HashMap<>(), new HashMap<>());
        final var admin = new AdminUserEntry("admin", "hash", true, new HashSet<>(), new HashMap<>(), new HashMap<>());
        assertTriggerAccess(true, owner);
        assertTriggerAccess(true, admin);
    }

    @Test
    public void test_a_blank_collection_falls_back_to_the_project_grant() {
        final var blank = new SaveTriggerRequest(TestGlobals.PROJECT, "", "t", List.of("CREATED"), "p");
        assertFalse(AuthorizationChecker.check(blank, manager(null, PermissionLevel.READ_WRITE)).isAllowed());
        assertTrue(AuthorizationChecker.check(blank, manager(PermissionLevel.READ_WRITE, null)).isAllowed());
    }

    @Test
    public void test_procedure_and_schedule_operations_ignore_data_grants() {
        final var schedule = new SaveScheduleRequest(TestGlobals.PROJECT, "sch", "p");
        schedule.setIntervalMs(1000L);
        final var scriptsOnly = manager(null, null);
        for (final var request : List.of(new SaveProcedureRequest(TestGlobals.PROJECT, "p", "return 1;"),
                new DeleteProcedureRequest(TestGlobals.PROJECT, "p"), schedule,
                new DeleteScheduleRequest(TestGlobals.PROJECT, "sch"))) {
            assertTrue(AuthorizationChecker.check(request, scriptsOnly).isAllowed(), request.getType().name());
        }
    }
}
