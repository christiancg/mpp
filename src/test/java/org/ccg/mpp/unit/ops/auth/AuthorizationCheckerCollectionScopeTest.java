package org.ccg.mpp.unit.ops.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.PkIndexEntry;
import org.ccg.mpp.data.admin.AdminProjectEntry;
import org.ccg.mpp.data.admin.AdminUserEntry;
import org.ccg.mpp.data.auth.PermissionLevel;
import org.ccg.mpp.data.auth.ScriptPermissionLevel;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.auth.AuthorizationChecker;
import org.ccg.mpp.ops.req.AggregateRequest;
import org.ccg.mpp.ops.req.CreateCollectionRequest;
import org.ccg.mpp.ops.req.CreateIndexRequest;
import org.ccg.mpp.ops.req.DeleteRequest;
import org.ccg.mpp.ops.req.DeleteTriggerRequest;
import org.ccg.mpp.ops.req.DropCollectionRequest;
import org.ccg.mpp.ops.req.FindByIdRequest;
import org.ccg.mpp.ops.req.ListCollectionsRequest;
import org.ccg.mpp.ops.req.OperationRequest;
import org.ccg.mpp.ops.req.SaveProcedureRequest;
import org.ccg.mpp.ops.req.SaveRequest;
import org.ccg.mpp.ops.req.SaveScheduleRequest;
import org.ccg.mpp.ops.req.SaveTriggerRequest;
import org.ccg.mpp.ops.req.TestTriggerRequest;
import org.ccg.mpp.ops.req.agg.BaseAggregationStep;
import org.ccg.mpp.ops.req.agg.step.JoinAggregationStep;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class AuthorizationCheckerCollectionScopeTest {
    private static final String PROJECT = "testProject";
    private static final String CARVED = "carvedColl";
    private static final String OPEN = "openColl";
    private static final String JOINED = "joinColl";
    private static final String OWNED_PROJECT = "carve_owner_project";
    private static final String OWNER = "carveowner";
    private static final String CARVED_KEY = TestGlobals.PROJECT + "|" + TestGlobals.COLL;

    private static final Cache cache = IocContainer.get(Cache.class);

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

    private static String key(String collName) {
        return PROJECT + Globals.COLL_IDENTIFIER_SEPARATOR + collName;
    }

    private AdminUserEntry userWith(Map<String, PermissionLevel> projectPerms, Map<String, PermissionLevel> collPerms) {
        return new AdminUserEntry("user", "hash", false, new HashSet<>(), projectPerms, collPerms);
    }

    private AdminUserEntry carvedOutUser() {
        final var projectPerms = new HashMap<String, PermissionLevel>();
        projectPerms.put(PROJECT, PermissionLevel.READ_WRITE);
        final var collPerms = new HashMap<String, PermissionLevel>();
        collPerms.put(key(CARVED), PermissionLevel.READ);
        return userWith(projectPerms, collPerms);
    }

    private static SaveRequest saveRequest(String collName) {
        final var req = new SaveRequest(PROJECT, collName);
        req.setObject(new JsonObject());
        return req;
    }

    private AggregateRequest aggregateWithSteps(String collName, List<BaseAggregationStep> steps) {
        final var req = new AggregateRequest(PROJECT, collName);
        req.setAggregationSteps(steps);
        return req;
    }

    private List<BaseAggregationStep> joinOnJoinedCollection() {
        final var steps = new ArrayList<BaseAggregationStep>();
        steps.add(new JoinAggregationStep(JOINED, "localField", "remoteField", "asField"));
        return steps;
    }

    private void setOwnedProjectInCache() {
        final var entry = new AdminProjectEntry(OWNED_PROJECT, new ArrayList<>(), List.of("user"));
        final var pkEntry = new PkIndexEntry(Globals.ADMIN_PROJECT_NAME, Globals.ADMIN_PROJECTS_COLLECTION_NAME,
                OWNED_PROJECT, 0, 10, 0);
        cache.putAdminProjectEntry(entry, pkEntry);
    }

    private static Stream<Arguments> writesUnderACarvedCollection() {
        final var delete = new DeleteRequest(PROJECT, CARVED);
        delete.set_id("doc1");
        return Stream.of(Arguments.of("test_collection_read_narrows_project_read_write_for_save", saveRequest(CARVED)),
                Arguments.of("test_collection_read_narrows_project_read_write_for_delete", delete),
                Arguments.of("test_collection_read_narrows_project_read_write_for_create_index",
                        new CreateIndexRequest(PROJECT, CARVED, "someField")),
                Arguments.of("test_collection_read_narrows_project_read_write_for_drop_collection",
                        new DropCollectionRequest(PROJECT, CARVED)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("writesUnderACarvedCollection")
    public void test_collection_read_narrows_project_read_write(String name, OperationRequest request) {
        assertFalse(AuthorizationChecker.check(request, carvedOutUser()).isAllowed(), name);
    }

    @Test
    public void test_collection_read_still_allows_reads_under_a_narrowed_project_grant() {
        final var user = carvedOutUser();
        final var findReq = new FindByIdRequest(PROJECT, CARVED);
        findReq.set_id("doc1");
        assertTrue(AuthorizationChecker.check(findReq, user).isAllowed());
        assertTrue(AuthorizationChecker.check(aggregateWithSteps(CARVED, new ArrayList<>()), user).isAllowed());
    }

    @Test
    public void test_collection_read_write_widens_project_read() {
        final var projectPerms = new HashMap<String, PermissionLevel>();
        projectPerms.put(PROJECT, PermissionLevel.READ);
        final var collPerms = new HashMap<String, PermissionLevel>();
        collPerms.put(key(OPEN), PermissionLevel.READ_WRITE);
        final var user = userWith(projectPerms, collPerms);
        assertTrue(AuthorizationChecker.check(saveRequest(OPEN), user).isAllowed());
        assertFalse(AuthorizationChecker.check(saveRequest(CARVED), user).isAllowed());
    }

    @Test
    public void test_collection_without_an_entry_falls_back_to_the_project_grant() {
        final var user = carvedOutUser();
        assertTrue(AuthorizationChecker.check(saveRequest(OPEN), user).isAllowed());
        user.getCollectionPermissions().put("otherProject" + Globals.COLL_IDENTIFIER_SEPARATOR + OPEN,
                PermissionLevel.READ);
        assertTrue(AuthorizationChecker.check(saveRequest(OPEN), user).isAllowed());
    }

    @Test
    public void test_blank_collection_name_resolves_against_the_project_grant() {
        final var projectPerms = new HashMap<String, PermissionLevel>();
        projectPerms.put(PROJECT, PermissionLevel.READ);
        final var collPerms = new HashMap<String, PermissionLevel>();
        collPerms.put(key(CARVED), PermissionLevel.READ);
        final var user = userWith(projectPerms, collPerms);
        assertTrue(AuthorizationChecker.check(new ListCollectionsRequest(PROJECT), user).isAllowed());
    }

    @Test
    public void test_admin_is_not_narrowed_by_a_collection_entry() {
        final var collPerms = new HashMap<String, PermissionLevel>();
        collPerms.put(key(CARVED), PermissionLevel.READ);
        final var admin = new AdminUserEntry("admin", "hash", true, new HashSet<>(), new HashMap<>(), collPerms);
        assertTrue(AuthorizationChecker.check(saveRequest(CARVED), admin).isAllowed());
    }

    @Test
    public void test_owner_is_not_narrowed_by_a_collection_entry() {
        setOwnedProjectInCache();
        final var collPerms = new HashMap<String, PermissionLevel>();
        collPerms.put(OWNED_PROJECT + Globals.COLL_IDENTIFIER_SEPARATOR + CARVED, PermissionLevel.READ);
        final var user = userWith(new HashMap<>(), collPerms);
        final var req = new SaveRequest(OWNED_PROJECT, CARVED);
        req.setObject(new JsonObject());
        assertTrue(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_join_collection_read_entry_still_permits_the_join() {
        final var projectPerms = new HashMap<String, PermissionLevel>();
        projectPerms.put(PROJECT, PermissionLevel.READ_WRITE);
        final var collPerms = new HashMap<String, PermissionLevel>();
        collPerms.put(key(JOINED), PermissionLevel.READ);
        final var user = userWith(projectPerms, collPerms);
        assertTrue(AuthorizationChecker.check(aggregateWithSteps(OPEN, joinOnJoinedCollection()), user).isAllowed());
    }

    @Test
    public void test_join_collection_entry_that_grants_nothing_denies_the_join() {
        final var projectPerms = new HashMap<String, PermissionLevel>();
        projectPerms.put("otherProject", PermissionLevel.READ_WRITE);
        final var user = userWith(projectPerms, new HashMap<>());
        assertFalse(AuthorizationChecker.check(aggregateWithSteps(OPEN, joinOnJoinedCollection()), user).isAllowed());
    }

    @Test
    public void test_create_collection_is_blocked_by_a_read_entry_for_that_name() {
        final var req = new CreateCollectionRequest(PROJECT, CARVED);
        assertFalse(AuthorizationChecker.check(req, carvedOutUser()).isAllowed());
    }

    private static AdminUserEntry managerWith(Map<String, PermissionLevel> collectionPermissions) {
        return managerNamed("manager", collectionPermissions);
    }

    private static AdminUserEntry managerNamed(String name, Map<String, PermissionLevel> collectionPermissions) {
        final var scriptPerms = Map.of(TestGlobals.PROJECT, ScriptPermissionLevel.MANAGE);
        return new AdminUserEntry(name, "hash", false, new HashSet<>(), new HashMap<>(),
                new HashMap<>(collectionPermissions), new HashMap<>(scriptPerms));
    }

    private static <T extends OperationRequest> T namingTheCarvedCollection(T request) throws Exception {
        final var field = OperationRequest.class.getDeclaredField("collectionName");
        field.setAccessible(true);
        field.set(request, TestGlobals.COLL);
        return request;
    }

    private static List<OperationRequest> triggerRequests() {
        return List.of(new SaveTriggerRequest(TestGlobals.PROJECT, TestGlobals.COLL, "t", List.of("CREATED"), "p"),
                new DeleteTriggerRequest(TestGlobals.PROJECT, TestGlobals.COLL, "t"),
                new TestTriggerRequest(TestGlobals.PROJECT, TestGlobals.COLL, "t", "CREATED", new JsonObject()));
    }

    @Test
    public void test_manage_with_a_read_carve_out_cannot_manage_triggers_there() {
        final var manager = managerWith(Map.of(CARVED_KEY, PermissionLevel.READ));
        for (final var request : triggerRequests()) {
            assertFalse(AuthorizationChecker.check(request, manager).isAllowed(),
                    "a trigger rewrites and vetoes writes, which READ does not cover: " + request.getType().name());
        }
    }

    @Test
    public void test_manage_with_a_read_write_collection_entry_can_manage_triggers() {
        final var manager = managerWith(Map.of(CARVED_KEY, PermissionLevel.READ_WRITE));
        for (final var request : triggerRequests()) {
            assertTrue(AuthorizationChecker.check(request, manager).isAllowed(), request.getType().name());
        }
    }

    @Test
    public void test_manage_without_a_collection_entry_needs_a_project_grant() {
        final var manager = managerWith(Map.of());
        for (final var request : triggerRequests()) {
            assertFalse(AuthorizationChecker.check(request, manager).isAllowed(), request.getType().name());
        }
    }

    @Test
    public void test_a_carve_out_on_another_collection_does_not_apply() {
        final var manager = new AdminUserEntry("manager", "hash", false, new HashSet<>(),
                new HashMap<>(Map.of(TestGlobals.PROJECT, PermissionLevel.READ_WRITE)),
                new HashMap<>(Map.of(TestGlobals.PROJECT + "|othercoll", PermissionLevel.READ)),
                new HashMap<>(Map.of(TestGlobals.PROJECT, ScriptPermissionLevel.MANAGE)));
        for (final var request : triggerRequests()) {
            assertTrue(AuthorizationChecker.check(request, manager).isAllowed(), request.getType().name());
        }
    }

    @Test
    public void test_a_procedure_or_schedule_save_ignores_collection_entries() throws Exception {
        final var manager = managerWith(Map.of(CARVED_KEY, PermissionLevel.READ));
        final var procedure = namingTheCarvedCollection(
                new SaveProcedureRequest(TestGlobals.PROJECT, "p", "return 1;"));
        final var schedule = new SaveScheduleRequest(TestGlobals.PROJECT, "sch", "p");
        schedule.setIntervalMs(1000L);
        namingTheCarvedCollection(schedule);

        assertTrue(AuthorizationChecker.check(procedure, manager).isAllowed());
        assertTrue(AuthorizationChecker.check(schedule, manager).isAllowed());
    }

    @Test
    public void test_an_owner_is_unaffected_by_a_carve_out() {
        final var owner = managerNamed(OWNER, Map.of(CARVED_KEY, PermissionLevel.READ));
        for (final var request : triggerRequests()) {
            assertTrue(AuthorizationChecker.check(request, owner).isAllowed(), request.getType().name());
        }
    }
}
