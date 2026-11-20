package org.ccg.mpp.unit.ops.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.PkIndexEntry;
import org.ccg.mpp.data.admin.AdminProjectEntry;
import org.ccg.mpp.data.admin.AdminUserEntry;
import org.ccg.mpp.data.auth.GlobalPermissionType;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.auth.AuthorizationChecker;
import org.ccg.mpp.ops.req.DropCollectionRequest;
import org.ccg.mpp.ops.req.DropProjectRequest;
import org.ccg.mpp.ops.req.FindByIdRequest;
import org.ccg.mpp.ops.req.SaveRequest;
import org.ccg.mpp.ops.req.SetProjectOwnersRequest;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class AuthorizationCheckerOwnershipTest {
    private static final Cache cache = IocContainer.get(Cache.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.standardTearDown();
    }

    private AdminUserEntry nonAdminWithDropPerm() {
        final var perms = new HashSet<GlobalPermissionType>();
        perms.add(GlobalPermissionType.DROP_PROJECT);
        return new AdminUserEntry("user", "hash", false, perms, new HashMap<>(), new HashMap<>());
    }

    private AdminUserEntry nonAdminNoPerms() {
        return new AdminUserEntry("user", "hash", false, new HashSet<>(), new HashMap<>(), new HashMap<>());
    }

    private void setOwnerInCache(String projectName) {
        final var entry = new AdminProjectEntry(projectName, new ArrayList<>(), List.of("user"));
        final var pkEntry = new PkIndexEntry(Globals.ADMIN_PROJECT_NAME, Globals.ADMIN_PROJECTS_COLLECTION_NAME,
                projectName, 0, 10, 0);
        cache.putAdminProjectEntry(entry, pkEntry);
    }

    @Test
    public void test_drop_project_requires_ownership_not_just_global_perm() {
        final var user = nonAdminWithDropPerm();
        final var req = new DropProjectRequest("some_project");
        assertFalse(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_drop_project_allowed_for_owner() {
        setOwnerInCache("owned_project");
        final var user = nonAdminNoPerms();
        final var req = new DropProjectRequest("owned_project");
        assertTrue(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_owner_can_do_all_ops_on_owned_project() {
        setOwnerInCache("owner_project");
        final var user = nonAdminNoPerms();

        final var saveReq = new SaveRequest("owner_project", "some_coll");
        assertTrue(AuthorizationChecker.check(saveReq, user).isAllowed());

        final var findReq = new FindByIdRequest("owner_project", "some_coll");
        assertTrue(AuthorizationChecker.check(findReq, user).isAllowed());

        final var dropCollReq = new DropCollectionRequest("owner_project", "some_coll");
        assertTrue(AuthorizationChecker.check(dropCollReq, user).isAllowed());
    }

    @Test
    public void test_owner_of_one_project_cannot_access_another() {
        setOwnerInCache("project1");
        final var user = nonAdminNoPerms();
        final var req = new SaveRequest("project2", "coll");
        assertFalse(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_set_project_owners_forbidden_for_non_admin() {
        final var user = nonAdminNoPerms();
        final var req = new SetProjectOwnersRequest("some_project");
        assertFalse(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_drop_project_no_entry_in_cache_forbidden() {
        final var user = nonAdminWithDropPerm();
        final var req = new DropProjectRequest("nonexistent_project");
        assertFalse(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_drop_project_project_exists_but_user_not_owner_forbidden() {
        setOwnerInCache("other_owners_project");
        final var user2 = new AdminUserEntry("user2", "hash", false, new HashSet<>(), new HashMap<>(), new HashMap<>());
        final var req = new DropProjectRequest("other_owners_project");
        assertFalse(AuthorizationChecker.check(req, user2).isAllowed());
    }

    @Test
    public void test_list_users_forbidden_for_non_admin() {
        final var user = nonAdminNoPerms();
        final var req = new org.ccg.mpp.ops.req.ListUsersRequest();
        assertFalse(AuthorizationChecker.check(req, user).isAllowed());
    }

    @Test
    public void test_run_script_allowed_for_owner() {
        setOwnerInCache("scripted_project");
        final var request = new org.ccg.mpp.ops.req.RunScriptRequest("scripted_project", "return 1;", null);
        assertTrue(AuthorizationChecker.check(request, nonAdminNoPerms()).isAllowed());
    }

    @Test
    public void test_run_script_denied_for_non_owner_without_permission() {
        setOwnerInCache("other_owned_project");
        final var request = new org.ccg.mpp.ops.req.RunScriptRequest("other_owned_project", "return 1;", null);
        final var stranger = new AdminUserEntry("stranger", "hash", false, new HashSet<>(), new HashMap<>(),
                new HashMap<>());
        assertFalse(AuthorizationChecker.check(request, stranger).isAllowed());
    }
}
