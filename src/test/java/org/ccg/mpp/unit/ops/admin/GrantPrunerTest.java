package org.ccg.mpp.unit.ops.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.data.admin.AdminProjectEntry;
import org.ccg.mpp.data.admin.AdminUserEntry;
import org.ccg.mpp.data.auth.PermissionLevel;
import org.ccg.mpp.data.auth.ScriptPermissionLevel;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.UserOperationHelper;
import org.ccg.mpp.ops.admin.CollectionOperationHelper;
import org.ccg.mpp.ops.admin.GrantRewrite;
import org.ccg.mpp.ops.admin.ProjectOperationHelper;
import org.ccg.mpp.ops.admin.RestampedRecords;
import org.ccg.mpp.ops.auth.AuthorizationChecker;
import org.ccg.mpp.ops.req.CreateCollectionRequest;
import org.ccg.mpp.ops.req.DeleteUserRequest;
import org.ccg.mpp.ops.req.DropCollectionRequest;
import org.ccg.mpp.ops.req.DropProjectRequest;
import org.ccg.mpp.ops.req.FindByIdRequest;
import org.ccg.mpp.ops.req.SetProjectOwnersRequest;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class GrantPrunerTest {
    private static final String BOB = "grantbob";
    private static final String CAROL = "grantcarol";
    private static final String OTHER_PROJECT = "otherdb";

    private Cache cache;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
        cache = IocContainer.get(Cache.class);
        AdminOperationHelper.saveUserEntry(grantedUser(BOB));
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static AdminUserEntry grantedUser(String name) {
        final var projects = new HashMap<String, PermissionLevel>();
        projects.put(TestGlobals.PROJECT, PermissionLevel.READ_WRITE);
        projects.put(OTHER_PROJECT, PermissionLevel.READ);
        final var collections = new HashMap<String, PermissionLevel>();
        collections.put(TestGlobals.PROJECT + "|" + TestGlobals.COLL, PermissionLevel.READ);
        collections.put(TestGlobals.PROJECT + "|" + TestGlobals.JOIN_COLL, PermissionLevel.READ);
        collections.put(OTHER_PROJECT + "|x", PermissionLevel.READ);
        final var scripts = new HashMap<String, ScriptPermissionLevel>();
        scripts.put(TestGlobals.PROJECT, ScriptPermissionLevel.MANAGE);
        scripts.put(OTHER_PROJECT, ScriptPermissionLevel.RUN);
        return new AdminUserEntry(name, "hash", false, new HashSet<>(), projects, collections, scripts);
    }

    private AdminUserEntry bob() {
        return cache.getAdminUserEntry(BOB);
    }

    private static boolean allowedToFind(AdminUserEntry user) {
        return AuthorizationChecker.check(new FindByIdRequest(TestGlobals.PROJECT, TestGlobals.COLL), user).isAllowed();
    }

    @Test
    public void test_dropping_a_project_removes_every_grant_naming_it() {
        assertTrue(allowedToFind(bob()));

        final var response = ProjectOperationHelper
                .processDropProjectOperation(new DropProjectRequest(TestGlobals.PROJECT), new RestampedRecords());

        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(Map.of(OTHER_PROJECT, PermissionLevel.READ), bob().getProjectPermissions());
        assertEquals(Map.of(OTHER_PROJECT + "|x", PermissionLevel.READ), bob().getCollectionPermissions());
        assertEquals(Map.of(OTHER_PROJECT, ScriptPermissionLevel.RUN), bob().getScriptPermissions());
    }

    @Test
    public void test_a_recreated_project_does_not_revive_the_old_grants() throws Exception {
        ProjectOperationHelper.processDropProjectOperation(new DropProjectRequest(TestGlobals.PROJECT),
                new RestampedRecords());
        AdminOperationHelper.saveProjectEntry(new AdminProjectEntry(TestGlobals.PROJECT, new java.util.ArrayList<>(),
                new java.util.ArrayList<>(List.of("someoneelse"))));
        AdminOperationHelper
                .saveCollectionEntry(new org.ccg.mpp.data.admin.AdminCollEntry(TestGlobals.PROJECT, TestGlobals.COLL));

        assertFalse(allowedToFind(bob()));
        assertFalse(bob().canRunScripts(TestGlobals.PROJECT));
        assertFalse(bob().canManageScripts(TestGlobals.PROJECT));
    }

    @Test
    public void test_dropping_a_collection_removes_only_its_collection_grant() {
        final var response = CollectionOperationHelper.processDropCollectionOperation(
                new DropCollectionRequest(TestGlobals.PROJECT, TestGlobals.COLL), new RestampedRecords());

        assertEquals(OperationStatus.OK, response.getStatus());
        assertFalse(bob().getCollectionPermissions().containsKey(TestGlobals.PROJECT + "|" + TestGlobals.COLL));
        assertTrue(bob().getCollectionPermissions().containsKey(TestGlobals.PROJECT + "|" + TestGlobals.JOIN_COLL));
        assertEquals(PermissionLevel.READ_WRITE, bob().getProjectPermissions().get(TestGlobals.PROJECT));
        assertEquals(ScriptPermissionLevel.MANAGE, bob().getScriptPermissions().get(TestGlobals.PROJECT));
    }

    @Test
    public void test_dropping_a_collection_nobody_holds_a_grant_on_changes_no_user() throws Exception {
        TestUtils.createTestJoinCollection();
        final var before = bob();

        CollectionOperationHelper.processDropCollectionOperation(
                new DropCollectionRequest(TestGlobals.PROJECT, TestGlobals.JOIN_COLL), new RestampedRecords());
        CollectionOperationHelper.processCreateCollectionOperation(
                new CreateCollectionRequest(TestGlobals.PROJECT, "unrelatedcollection"));
        CollectionOperationHelper.processDropCollectionOperation(
                new DropCollectionRequest(TestGlobals.PROJECT, "unrelatedcollection"), new RestampedRecords());

        assertEquals(before.getProjectPermissions(), bob().getProjectPermissions());
    }

    private long bobVersion() {
        return AdminOperationHelper.versionOf(cache.getPkIndexAdminUserEntry(BOB));
    }

    @Test
    public void test_a_coordinator_drop_writes_the_pruned_grant_at_a_new_version() {
        final var before = bobVersion();

        CollectionOperationHelper.processDropCollectionOperation(
                new DropCollectionRequest(TestGlobals.PROJECT, TestGlobals.COLL), new RestampedRecords());

        assertFalse(bob().getCollectionPermissions().containsKey(TestGlobals.PROJECT + "|" + TestGlobals.COLL));
        assertTrue(bobVersion() > before, "the coordinator's prune must outrank the grant a lagging node kept");
    }

    @Test
    public void test_a_replicated_drop_prunes_at_the_version_the_user_already_had() {
        final var before = bobVersion();

        final var response = CollectionOperationHelper.dropCollection(TestGlobals.PROJECT, TestGlobals.COLL, true,
                GrantRewrite.KEEP);

        assertEquals(OperationStatus.OK, response.getStatus());
        assertFalse(bob().getCollectionPermissions().containsKey(TestGlobals.PROJECT + "|" + TestGlobals.COLL));
        assertEquals(before, bobVersion());
    }

    @Test
    public void test_a_coordinator_user_delete_writes_the_owner_strip_at_a_new_version() throws Exception {
        makeCarolOwnerOfTheTestProject();
        final var before = AdminOperationHelper.versionOf(cache.getPkIndexAdminProjectEntry(TestGlobals.PROJECT));
        final var request = new DeleteUserRequest();
        request.setUsername(CAROL);

        UserOperationHelper.processDeleteUser(request, new RestampedRecords());

        assertEquals(List.of(), cache.getAdminProjectEntry(TestGlobals.PROJECT).getOwners());
        assertTrue(AdminOperationHelper.versionOf(cache.getPkIndexAdminProjectEntry(TestGlobals.PROJECT)) > before);
    }

    private void makeCarolOwnerOfTheTestProject() throws Exception {
        AdminOperationHelper.saveUserEntry(grantedUser(CAROL));
        assertTrue(AdminOperationHelper.updateProjectOwners(TestGlobals.PROJECT, List.of(CAROL)));
    }

    @Test
    public void test_deleting_a_user_removes_it_from_the_owners_and_leaves_the_project_ownerless() throws Exception {
        makeCarolOwnerOfTheTestProject();
        final var request = new DeleteUserRequest();
        request.setUsername(CAROL);

        UserOperationHelper.processDeleteUser(request, new RestampedRecords());

        assertEquals(List.of(), cache.getAdminProjectEntry(TestGlobals.PROJECT).getOwners());
    }

    @Test
    public void test_a_user_created_under_a_deleted_name_does_not_inherit_ownership() throws Exception {
        makeCarolOwnerOfTheTestProject();
        final var request = new DeleteUserRequest();
        request.setUsername(CAROL);
        UserOperationHelper.processDeleteUser(request, new RestampedRecords());

        AdminOperationHelper.saveUserEntry(
                new AdminUserEntry(CAROL, "hash", false, new HashSet<>(), new HashMap<>(), new HashMap<>()));

        assertFalse(cache.getAdminProjectEntry(TestGlobals.PROJECT).isOwner(CAROL));
        final var drop = new org.ccg.mpp.ops.req.DropProjectRequest(TestGlobals.PROJECT);
        assertFalse(AuthorizationChecker.check(drop, cache.getAdminUserEntry(CAROL)).isAllowed());
    }

    @Test
    public void test_an_ownerless_project_can_be_given_owners_again() throws Exception {
        makeCarolOwnerOfTheTestProject();
        final var delete = new DeleteUserRequest();
        delete.setUsername(CAROL);
        UserOperationHelper.processDeleteUser(delete, new RestampedRecords());
        final var setOwners = new SetProjectOwnersRequest(TestGlobals.PROJECT);
        setOwners.setOwners(List.of(BOB));

        final var response = ProjectOperationHelper.processSetProjectOwners(setOwners);

        assertEquals(OperationStatus.OK, response.getStatus());
        assertNotNull(cache.getAdminProjectEntry(TestGlobals.PROJECT));
        assertTrue(cache.getAdminProjectEntry(TestGlobals.PROJECT).isOwner(BOB));
    }

    @Test
    public void test_deleting_a_user_keeps_the_other_owners() throws Exception {
        makeCarolOwnerOfTheTestProject();
        assertTrue(AdminOperationHelper.updateProjectOwners(TestGlobals.PROJECT, List.of(CAROL, BOB)));
        final var request = new DeleteUserRequest();
        request.setUsername(CAROL);

        UserOperationHelper.processDeleteUser(request, new RestampedRecords());

        assertEquals(List.of(BOB), cache.getAdminProjectEntry(TestGlobals.PROJECT).getOwners());
    }
}
