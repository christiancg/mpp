package org.ccg.mpp.unit.ops.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import org.ccg.mpp.data.admin.AdminUserEntry;
import org.ccg.mpp.data.auth.GlobalPermissionType;
import org.ccg.mpp.data.auth.PermissionLevel;
import org.ccg.mpp.data.auth.ScriptPermissionLevel;
import org.ccg.mpp.ops.auth.AuthorizationChecker;
import org.ccg.mpp.ops.req.AggregateRequest;
import org.ccg.mpp.ops.req.ArmFaultRequest;
import org.ccg.mpp.ops.req.AwaitFaultRequest;
import org.ccg.mpp.ops.req.CloseConnectionRequest;
import org.ccg.mpp.ops.req.CommitTransactionRequest;
import org.ccg.mpp.ops.req.CreateProjectRequest;
import org.ccg.mpp.ops.req.CreateUserRequest;
import org.ccg.mpp.ops.req.FindByIdRequest;
import org.ccg.mpp.ops.req.GetProjectStatsRequest;
import org.ccg.mpp.ops.req.GetSchemaRequest;
import org.ccg.mpp.ops.req.ListProjectsRequest;
import org.ccg.mpp.ops.req.OperationRequest;
import org.ccg.mpp.ops.req.ReleaseFaultRequest;
import org.ccg.mpp.ops.req.RollbackTransactionRequest;
import org.ccg.mpp.ops.req.SaveRequest;
import org.ccg.mpp.ops.req.StartTransactionRequest;
import org.ccg.mpp.ops.req.agg.BaseAggregationStep;
import org.ccg.mpp.ops.req.agg.step.FilterAggregationStep;
import org.ccg.mpp.ops.req.agg.step.JoinAggregationStep;
import org.junit.jupiter.api.Test;

public class AuthorizationCheckerTest {
    private AdminUserEntry createAdminUser() {
        return new AdminUserEntry("admin", "hash", true, new HashSet<>(), new HashMap<>(), new HashMap<>());
    }

    private AdminUserEntry createNonAdminUser() {
        return new AdminUserEntry("user", "hash", false, new HashSet<>(), new HashMap<>(), new HashMap<>());
    }

    @Test
    public void test_admin_user_allowed_for_every_operation_type() {
        final var admin = createAdminUser();
        final var req = new SaveRequest("testProject", "testColl");
        assertTrue(AuthorizationChecker.check(req, admin).isAllowed());
    }

    @Test
    public void test_non_admin_denied_user_admin_ops() {
        final var user = createNonAdminUser();
        final var createUserReq = new CreateUserRequest();
        assertFalse(AuthorizationChecker.check(createUserReq, user).isAllowed());
    }

    @Test
    public void test_transaction_control_is_allowed_for_a_user_with_no_grants() {
        final var user = createNonAdminUser();

        assertTrue(AuthorizationChecker.check(new StartTransactionRequest(), user).isAllowed());
        assertTrue(AuthorizationChecker.check(new CommitTransactionRequest(), user).isAllowed());
        assertTrue(AuthorizationChecker.check(new RollbackTransactionRequest(), user).isAllowed());
    }

    @Test
    public void test_a_write_inside_a_transaction_is_still_authorized_on_its_own() {
        final var projectPerms = new HashMap<String, PermissionLevel>();
        projectPerms.put("testProject", PermissionLevel.READ);
        final var user = new AdminUserEntry("user", "hash", false, new HashSet<>(), projectPerms, new HashMap<>());

        assertTrue(AuthorizationChecker.check(new StartTransactionRequest(), user).isAllowed());
        assertFalse(AuthorizationChecker.check(new SaveRequest("testProject", "testColl"), user).isAllowed(),
                "opening a transaction must not widen what the user may write");
    }

    @Test
    public void test_get_schema_needs_read_on_the_collection() {
        final var readerPerms = new HashMap<String, PermissionLevel>();
        readerPerms.put("testProject", PermissionLevel.READ);
        final var reader = new AdminUserEntry("reader", "hash", false, new HashSet<>(), readerPerms, new HashMap<>());
        final var request = new GetSchemaRequest("testProject", "testColl");

        assertTrue(AuthorizationChecker.check(request, reader).isAllowed());
        assertFalse(AuthorizationChecker.check(request, createNonAdminUser()).isAllowed());
    }

    @Test
    public void test_get_project_stats_is_allowed_for_any_authenticated_user() {
        final var request = new GetProjectStatsRequest();

        assertTrue(AuthorizationChecker.check(request, createNonAdminUser()).isAllowed());
        assertTrue(AuthorizationChecker.check(request, createAdminUser()).isAllowed());
        assertFalse(AuthorizationChecker.check(request, null).isAllowed());
    }

    @Test
    public void test_can_read_collection_follows_admin_and_grants() {
        final var projectPerms = new HashMap<String, PermissionLevel>();
        projectPerms.put("testProject", PermissionLevel.READ);
        final var projectReader = new AdminUserEntry("projectReader", "hash", false, new HashSet<>(), projectPerms,
                new HashMap<>());
        final var collPerms = new HashMap<String, PermissionLevel>();
        collPerms.put("testProject|testColl", PermissionLevel.READ_WRITE);
        final var collWriter = new AdminUserEntry("collWriter", "hash", false, new HashSet<>(), new HashMap<>(),
                collPerms);

        assertTrue(AuthorizationChecker.canReadCollection(createAdminUser(), "testProject", "testColl"));
        assertTrue(AuthorizationChecker.canReadCollection(projectReader, "testProject", "otherColl"));
        assertTrue(AuthorizationChecker.canReadCollection(collWriter, "testProject", "testColl"));
        assertFalse(AuthorizationChecker.canReadCollection(collWriter, "testProject", "otherColl"));
        assertFalse(AuthorizationChecker.canReadCollection(createNonAdminUser(), "testProject", "testColl"));
        assertFalse(AuthorizationChecker.canReadCollection(null, "testProject", "testColl"));
    }

    @Test
    public void test_list_transactions_admin_only() {
        final var request = new org.ccg.mpp.ops.req.ListTransactionsRequest();
        assertTrue(AuthorizationChecker.check(request, createAdminUser()).isAllowed());
        assertFalse(AuthorizationChecker.check(request, createNonAdminUser()).isAllowed());
    }

    @Test
    public void test_fault_operations_are_admin_only() {
        final List<OperationRequest> requests = List.of(new ArmFaultRequest(), new AwaitFaultRequest(),
                new ReleaseFaultRequest());
        for (final var request : requests) {
            assertTrue(AuthorizationChecker.check(request, createAdminUser()).isAllowed());
            assertFalse(AuthorizationChecker.check(request, createNonAdminUser()).isAllowed());
        }
    }

    @Test
    public void test_create_project_requires_global_permission_allow() {
        final var perms = new HashSet<GlobalPermissionType>();
        perms.add(GlobalPermissionType.CREATE_PROJECT);
        final var user = new AdminUserEntry("user", "hash", false, perms, new HashMap<>(), new HashMap<>());
        final var req = new CreateProjectRequest("newProject");
        assertTrue(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_create_project_requires_global_permission_deny() {
        final var user = createNonAdminUser();
        final var req = new CreateProjectRequest("newProject");
        assertFalse(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_find_by_id_with_collection_permission() {
        final var collPerms = new HashMap<String, PermissionLevel>();
        collPerms.put("testProject|testColl", PermissionLevel.READ);
        final var user = new AdminUserEntry("user", "hash", false, new HashSet<>(), new HashMap<>(), collPerms);
        final var req = new FindByIdRequest("testProject", "testColl");
        req.set_id("123");
        assertTrue(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_find_by_id_falls_back_to_project_permission() {
        final var projectPerms = new HashMap<String, PermissionLevel>();
        projectPerms.put("testProject", PermissionLevel.READ);
        final var user = new AdminUserEntry("user", "hash", false, new HashSet<>(), projectPerms, new HashMap<>());
        final var req = new FindByIdRequest("testProject", "testColl");
        req.set_id("123");
        assertTrue(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_save_requires_read_write_denied_with_read_only() {
        final var collPerms = new HashMap<String, PermissionLevel>();
        collPerms.put("testProject|testColl", PermissionLevel.READ);
        final var user = new AdminUserEntry("user", "hash", false, new HashSet<>(), new HashMap<>(), collPerms);
        final var req = new SaveRequest("testProject", "testColl");
        req.setObject(new org.ccg.mpp.ejson.elements.JsonObject());
        assertFalse(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_save_allowed_with_read_write() {
        final var collPerms = new HashMap<String, PermissionLevel>();
        collPerms.put("testProject|testColl", PermissionLevel.READ_WRITE);
        final var user = new AdminUserEntry("user", "hash", false, new HashSet<>(), new HashMap<>(), collPerms);
        final var req = new SaveRequest("testProject", "testColl");
        req.setObject(new org.ccg.mpp.ejson.elements.JsonObject());
        assertTrue(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_list_projects_always_allowed() {
        final var user = createNonAdminUser();
        final var req = new ListProjectsRequest();
        assertTrue(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_close_connection_allowed() {
        final var user = createNonAdminUser();
        final var req = new CloseConnectionRequest();
        assertTrue(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_missing_all_permissions_denied() {
        final var user = createNonAdminUser();
        final var req = new SaveRequest("testProject", "testColl");
        req.setObject(new org.ccg.mpp.ejson.elements.JsonObject());
        assertFalse(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_null_user() {
        final var req = new SaveRequest("testProject", "testColl");
        assertFalse(AuthorizationChecker.check(req, null).isAllowed());
    }

    private AggregateRequest aggregateWithSteps(List<BaseAggregationStep> steps) {
        final var req = new AggregateRequest("testProject", "testColl");
        req.setAggregationSteps(steps);
        return req;
    }

    private JoinAggregationStep joinStep(String joinCollection) {
        return new JoinAggregationStep(joinCollection, "localField", "remoteField", "asField");
    }

    @Test
    public void test_aggregate_join_user_has_read_on_both_collections_allowed() {
        final var collPerms = new HashMap<String, PermissionLevel>();
        collPerms.put("testProject|testColl", PermissionLevel.READ);
        collPerms.put("testProject|joinColl", PermissionLevel.READ);
        final var user = new AdminUserEntry("user", "hash", false, new HashSet<>(), new HashMap<>(), collPerms);
        final var steps = new ArrayList<BaseAggregationStep>();
        steps.add(joinStep("joinColl"));
        final var req = aggregateWithSteps(steps);
        assertTrue(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_aggregate_join_user_lacks_join_collection_permission_denied() {
        final var collPerms = new HashMap<String, PermissionLevel>();
        collPerms.put("testProject|testColl", PermissionLevel.READ);
        final var user = new AdminUserEntry("user", "hash", false, new HashSet<>(), new HashMap<>(), collPerms);
        final var steps = new ArrayList<BaseAggregationStep>();
        steps.add(joinStep("joinColl"));
        final var req = aggregateWithSteps(steps);
        final var result = AuthorizationChecker.check(req, user);
        assertFalse(result.isAllowed());
        assertEquals("action is forbidden, no permissions", result.getReason());
    }

    @Test
    public void test_aggregate_join_project_level_read_covers_join_collection_allowed() {
        final var projectPerms = new HashMap<String, PermissionLevel>();
        projectPerms.put("testProject", PermissionLevel.READ);
        final var user = new AdminUserEntry("user", "hash", false, new HashSet<>(), projectPerms, new HashMap<>());
        final var steps = new ArrayList<BaseAggregationStep>();
        steps.add(joinStep("joinColl"));
        final var req = aggregateWithSteps(steps);
        assertTrue(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_aggregate_join_collection_level_main_but_join_missing_denied() {
        final var collPerms = new HashMap<String, PermissionLevel>();
        collPerms.put("testProject|testColl", PermissionLevel.READ);
        final var user = new AdminUserEntry("user", "hash", false, new HashSet<>(), new HashMap<>(), collPerms);
        final var steps = new ArrayList<BaseAggregationStep>();
        steps.add(new FilterAggregationStep(null));
        steps.add(joinStep("joinColl"));
        final var req = aggregateWithSteps(steps);
        assertFalse(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_aggregate_multiple_joins_one_forbidden_denied() {
        final var collPerms = new HashMap<String, PermissionLevel>();
        collPerms.put("testProject|testColl", PermissionLevel.READ);
        collPerms.put("testProject|joinCollA", PermissionLevel.READ);
        final var user = new AdminUserEntry("user", "hash", false, new HashSet<>(), new HashMap<>(), collPerms);
        final var steps = new ArrayList<BaseAggregationStep>();
        steps.add(joinStep("joinCollA"));
        steps.add(joinStep("joinCollB"));
        final var req = aggregateWithSteps(steps);
        assertFalse(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_aggregate_without_join_unchanged_behavior_allowed() {
        final var collPerms = new HashMap<String, PermissionLevel>();
        collPerms.put("testProject|testColl", PermissionLevel.READ);
        final var user = new AdminUserEntry("user", "hash", false, new HashSet<>(), new HashMap<>(), collPerms);
        final var steps = new ArrayList<BaseAggregationStep>();
        steps.add(new FilterAggregationStep(null));
        final var req = aggregateWithSteps(steps);
        assertTrue(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_aggregate_null_steps_allowed() {
        final var collPerms = new HashMap<String, PermissionLevel>();
        collPerms.put("testProject|testColl", PermissionLevel.READ);
        final var user = new AdminUserEntry("user", "hash", false, new HashSet<>(), new HashMap<>(), collPerms);
        final var req = new AggregateRequest("testProject", "testColl");
        assertTrue(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_aggregate_join_admin_allowed() {
        final var admin = createAdminUser();
        final var steps = new ArrayList<BaseAggregationStep>();
        steps.add(joinStep("joinColl"));
        final var req = aggregateWithSteps(steps);
        assertTrue(AuthorizationChecker.check(req, admin).isAllowed());
    }

    @Test
    public void test_aggregate_join_read_write_covers_read_allowed() {
        final var collPerms = new HashMap<String, PermissionLevel>();
        collPerms.put("testProject|testColl", PermissionLevel.READ);
        collPerms.put("testProject|joinColl", PermissionLevel.READ_WRITE);
        final var user = new AdminUserEntry("user", "hash", false, new HashSet<>(), new HashMap<>(), collPerms);
        final var steps = new ArrayList<BaseAggregationStep>();
        steps.add(joinStep("joinColl"));
        final var req = aggregateWithSteps(steps);
        assertTrue(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_run_script_allowed_for_admin() {
        final var request = new org.ccg.mpp.ops.req.RunScriptRequest("testProject", "return 1;", null);
        assertTrue(AuthorizationChecker.check(request, createAdminUser()).isAllowed());
    }

    @Test
    public void test_run_script_denied_without_script_permission() {
        final var projectPerms = new HashMap<String, PermissionLevel>();
        projectPerms.put("testProject", PermissionLevel.READ_WRITE);
        final var user = new AdminUserEntry("user", "hash", false, new HashSet<>(), projectPerms, new HashMap<>());
        final var request = new org.ccg.mpp.ops.req.RunScriptRequest("testProject", "return 1;", null);
        assertFalse(AuthorizationChecker.check(request, user).isAllowed());
    }

    @Test
    public void test_run_script_allowed_with_script_permission_for_that_project() {
        final var scriptPerms = new HashMap<String, ScriptPermissionLevel>();
        scriptPerms.put("testProject", ScriptPermissionLevel.RUN);
        final var user = new AdminUserEntry("user", "hash", false, new HashSet<>(), new HashMap<>(), new HashMap<>(),
                scriptPerms);
        final var request = new org.ccg.mpp.ops.req.RunScriptRequest("testProject", "return 1;", null);
        assertTrue(AuthorizationChecker.check(request, user).isAllowed());
    }

    @Test
    public void test_run_script_denied_for_a_different_project() {
        final var scriptPerms = new HashMap<String, ScriptPermissionLevel>();
        scriptPerms.put("testProject", ScriptPermissionLevel.RUN);
        final var projectPerms = new HashMap<String, PermissionLevel>();
        projectPerms.put("otherProject", PermissionLevel.READ_WRITE);
        final var user = new AdminUserEntry("user", "hash", false, new HashSet<>(), projectPerms, new HashMap<>(),
                scriptPerms);
        final var request = new org.ccg.mpp.ops.req.RunScriptRequest("otherProject", "return 1;", null);
        assertFalse(AuthorizationChecker.check(request, user).isAllowed());
    }

    @Test
    public void test_run_script_denied_when_grant_is_false() {
        final var scriptPerms = new HashMap<String, ScriptPermissionLevel>();
        scriptPerms.put("testProject", ScriptPermissionLevel.NONE);
        final var user = new AdminUserEntry("user", "hash", false, new HashSet<>(), new HashMap<>(), new HashMap<>(),
                scriptPerms);
        final var request = new org.ccg.mpp.ops.req.RunScriptRequest("testProject", "return 1;", null);
        assertFalse(AuthorizationChecker.check(request, user).isAllowed());
    }

    @Test
    public void test_run_script_grant_does_not_imply_data_access() {
        final var scriptPerms = new HashMap<String, ScriptPermissionLevel>();
        scriptPerms.put("testProject", ScriptPermissionLevel.RUN);
        final var user = new AdminUserEntry("user", "hash", false, new HashSet<>(), new HashMap<>(), new HashMap<>(),
                scriptPerms);
        assertTrue(AuthorizationChecker
                .check(new org.ccg.mpp.ops.req.RunScriptRequest("testProject", "return 1;", null), user).isAllowed());
        assertFalse(AuthorizationChecker.check(new SaveRequest("testProject", "testColl"), user).isAllowed());
        assertFalse(AuthorizationChecker.check(new FindByIdRequest("testProject", "testColl"), user).isAllowed());
    }
}
