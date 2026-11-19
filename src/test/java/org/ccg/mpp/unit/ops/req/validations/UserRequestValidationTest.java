package org.ccg.mpp.unit.ops.req.validations;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Set;
import org.ccg.mpp.data.auth.GlobalPermissionType;
import org.ccg.mpp.data.auth.PermissionLevel;
import org.ccg.mpp.data.auth.ScriptPermissionLevel;
import org.ccg.mpp.ops.req.AuthenticateRequest;
import org.ccg.mpp.ops.req.ChangePermissionsRequest;
import org.ccg.mpp.ops.req.CreateUserRequest;
import org.ccg.mpp.ops.req.DeleteUserRequest;
import org.ccg.mpp.ops.req.validations.RequestValidator;
import org.junit.jupiter.api.Test;

public class UserRequestValidationTest {
    @Test
    public void test_authenticate_requires_username() {
        final var req = new AuthenticateRequest();
        req.setUsername(null);
        req.setPassword("password");
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void test_authenticate_requires_password() {
        final var req = new AuthenticateRequest();
        req.setUsername("user");
        req.setPassword(null);
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void test_authenticate_validates_username_pattern() {
        final var req = new AuthenticateRequest();
        req.setUsername("ab");
        req.setPassword("password");
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void test_authenticate_valid_request() {
        final var req = new AuthenticateRequest();
        req.setUsername("valid_user");
        req.setPassword("password");
        assertTrue(RequestValidator.validate(req).isValid());
    }

    @Test
    public void test_create_user_requires_long_password() {
        final var req = new CreateUserRequest();
        req.setUsername("user");
        req.setPassword("short");
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void test_create_user_rejects_admin_project() {
        final var req = new CreateUserRequest();
        req.setUsername("user");
        req.setPassword("password123");
        req.setProjectPermissions(Map.of("admin", PermissionLevel.READ));
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void test_create_user_rejects_bad_collection_key_format() {
        final var req = new CreateUserRequest();
        req.setUsername("user");
        req.setPassword("password123");
        req.setCollectionPermissions(Map.of("invalid_key", PermissionLevel.READ));
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void test_a_collection_key_with_a_trailing_separator_is_refused() {
        final var req = new CreateUserRequest();
        req.setUsername("user");
        req.setPassword("password123");
        req.setCollectionPermissions(Map.of("mydb|secret|", PermissionLevel.READ));
        assertFalse(RequestValidator.validate(req).isValid(),
                "a key that can never match project|collection must not be stored as a no-op grant");
    }

    @Test
    public void test_a_collection_key_with_a_doubled_separator_is_refused() {
        for (final var key : java.util.List.of("mydb||secret", "mydb|secret||")) {
            final var req = new CreateUserRequest();
            req.setUsername("user");
            req.setPassword("password123");
            req.setCollectionPermissions(Map.of(key, PermissionLevel.READ));
            assertFalse(RequestValidator.validate(req).isValid(), key);
        }
    }

    @Test
    public void test_create_user_valid_request() {
        final var req = new CreateUserRequest();
        req.setUsername("user");
        req.setPassword("password123");
        req.setGlobalPermissions(Set.of(GlobalPermissionType.CREATE_PROJECT));
        req.setProjectPermissions(Map.of("mydb", PermissionLevel.READ_WRITE));
        req.setCollectionPermissions(Map.of("mydb|coll", PermissionLevel.READ));
        assertTrue(RequestValidator.validate(req).isValid());
    }

    @Test
    public void test_delete_user_validates_username() {
        final var req = new DeleteUserRequest();
        req.setUsername("ab");
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void test_delete_user_valid() {
        final var req = new DeleteUserRequest();
        req.setUsername("valid_user");
        assertTrue(RequestValidator.validate(req).isValid());
    }

    @Test
    public void test_change_permissions_valid() {
        final var req = new ChangePermissionsRequest();
        req.setUsername("user");
        req.setAdmin(true);
        assertTrue(RequestValidator.validate(req).isValid());
    }

    @Test
    public void test_change_permissions_rejects_admin_project() {
        final var req = new ChangePermissionsRequest();
        req.setUsername("user");
        req.setProjectPermissions(Map.of("admin", PermissionLevel.READ));
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void test_create_user_requires_username() {
        final var req = new CreateUserRequest();
        req.setUsername(null);
        req.setPassword("password123");
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void test_create_user_validates_username_pattern() {
        final var req = new CreateUserRequest();
        req.setUsername("ab");
        req.setPassword("password123");
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void test_delete_user_requires_username_not_null() {
        final var req = new DeleteUserRequest();
        req.setUsername(null);
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void test_change_permissions_requires_username() {
        final var req = new ChangePermissionsRequest();
        req.setUsername(null);
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void test_change_permissions_validates_username_pattern() {
        final var req = new ChangePermissionsRequest();
        req.setUsername("ab");
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void test_create_user_rejects_bad_project_name_in_permissions() {
        final var req = new CreateUserRequest();
        req.setUsername("user");
        req.setPassword("password123");
        req.setProjectPermissions(Map.of("ab", PermissionLevel.READ));
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void test_create_user_rejects_admin_project_in_collection_permission() {
        final var req = new CreateUserRequest();
        req.setUsername("user");
        req.setPassword("password123");
        req.setCollectionPermissions(Map.of("admin|my_coll", PermissionLevel.READ));
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void test_create_user_rejects_bad_project_name_in_collection_permission() {
        final var req = new CreateUserRequest();
        req.setUsername("user");
        req.setPassword("password123");
        req.setCollectionPermissions(Map.of("ab|my_coll", PermissionLevel.READ));
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void test_create_user_rejects_bad_collection_name_in_permission() {
        final var req = new CreateUserRequest();
        req.setUsername("user");
        req.setPassword("password123");
        req.setCollectionPermissions(Map.of("mydb|ab", PermissionLevel.READ));
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void test_create_user_invalid_project_permission_level() {
        final var req = new CreateUserRequest();
        req.setUsername("user");
        req.setPassword("password123");
        final var rawPerms = new org.ccg.mpp.ejson.elements.JsonObject();
        rawPerms.add("mydb", new org.ccg.mpp.ejson.elements.JsonString("INVALID_LEVEL"));
        final var result = RequestValidator.validate(req);
        assertTrue(result.isValid());
    }

    @Test
    public void test_set_project_owners_valid() {
        final var req = new org.ccg.mpp.ops.req.SetProjectOwnersRequest("mydb");
        assertTrue(RequestValidator.validate(req).isValid());
    }

    @Test
    public void test_set_project_owners_requires_project_name() {
        final var req = new org.ccg.mpp.ops.req.SetProjectOwnersRequest(null);
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void test_set_project_owners_rejects_admin_project() {
        final var req = new org.ccg.mpp.ops.req.SetProjectOwnersRequest("admin");
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void test_set_project_owners_with_valid_owners() {
        final var cache = org.ccg.mpp.ioc.IocContainer.get(org.ccg.mpp.cache.Cache.class);
        final var userEntry = new org.ccg.mpp.data.admin.AdminUserEntry("valid_owner", "hash", false,
                new java.util.HashSet<>(), new java.util.HashMap<>(), new java.util.HashMap<>());
        final var pkEntry = new org.ccg.mpp.data.PkIndexEntry(org.ccg.mpp.config.Globals.ADMIN_PROJECT_NAME,
                org.ccg.mpp.config.Globals.ADMIN_USERS_COLLECTION_NAME, "valid_owner", 0, 10, 0);
        cache.putAdminUserEntry(userEntry, pkEntry);

        final var req = new org.ccg.mpp.ops.req.SetProjectOwnersRequest("mydb");
        req.setOwners(java.util.List.of("valid_owner"));
        assertTrue(RequestValidator.validate(req).isValid());

        cache.removeAdminUserEntry("valid_owner");
    }

    @Test
    public void test_set_project_owners_rejects_nonexistent_user() {
        final var req = new org.ccg.mpp.ops.req.SetProjectOwnersRequest("mydb");
        req.setOwners(java.util.List.of("ghost_user"));
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void test_set_project_owners_rejects_bad_username() {
        final var req = new org.ccg.mpp.ops.req.SetProjectOwnersRequest("mydb");
        req.setOwners(java.util.List.of("ab"));
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void test_create_user_invalid_coll_permission_level() {
        final var req = new CreateUserRequest();
        req.setUsername("user");
        req.setPassword("password123");
        final var rawCollPerms = new org.ccg.mpp.ejson.elements.JsonObject();
        rawCollPerms.add("valid_project|valid_coll", new org.ccg.mpp.ejson.elements.JsonString("NOT_A_VALID_LEVEL"));
        // inject via reflection to bypass the setter (which only accepts valid PermissionLevel)
        try {
            final var field = CreateUserRequest.class.getSuperclass().getDeclaredField("collectionPermissions");
            field.setAccessible(true);
            field.set(req, rawCollPerms);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void test_create_user_accepts_script_permissions() {
        final var req = new CreateUserRequest();
        req.setUsername("user");
        req.setPassword("password123");
        req.setScriptPermissions(Map.of("mydb", ScriptPermissionLevel.RUN));
        assertTrue(RequestValidator.validate(req).isValid());
    }

    @Test
    public void test_create_user_rejects_reserved_project_in_script_permissions() {
        final var req = new CreateUserRequest();
        req.setUsername("user");
        req.setPassword("password123");
        req.setScriptPermissions(Map.of("admin", ScriptPermissionLevel.RUN));
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void test_create_user_rejects_invalid_project_name_in_script_permissions() {
        final var req = new CreateUserRequest();
        req.setUsername("user");
        req.setPassword("password123");
        req.setScriptPermissions(Map.of("a", ScriptPermissionLevel.RUN));
        assertFalse(RequestValidator.validate(req).isValid());
    }

    // READ is a collection permission level, not a script one: a typo'd level must fail loudly rather
    // than read as NONE, which the operator could not then explain.
    @Test
    public void test_change_permissions_rejects_unknown_script_permission_level() {
        final var req = org.ccg.mpp.ops.req.RequestParser.parseRequest(
                "{\"type\":\"CHANGE_PERMISSIONS\",\"username\":\"user\",\"scriptPermissions\":{\"mydb\":\"READ\"}}");
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void test_change_permissions_accepts_manage_level() {
        final var req = (ChangePermissionsRequest) org.ccg.mpp.ops.req.RequestParser.parseRequest(
                "{\"type\":\"CHANGE_PERMISSIONS\",\"username\":\"user\",\"scriptPermissions\":{\"mydb\":\"MANAGE\"}}");
        assertTrue(RequestValidator.validate(req).isValid());
        assertEquals(Map.of("mydb", ScriptPermissionLevel.MANAGE), req.getScriptPermissions());
    }

    @Test
    public void test_change_permissions_parses_boolean_script_permission() {
        final var req = (ChangePermissionsRequest) org.ccg.mpp.ops.req.RequestParser.parseRequest(
                "{\"type\":\"CHANGE_PERMISSIONS\",\"username\":\"user\",\"scriptPermissions\":{\"mydb\":true}}");
        assertTrue(RequestValidator.validate(req).isValid());
        assertEquals(Map.of("mydb", ScriptPermissionLevel.RUN), req.getScriptPermissions());
    }

    @Test
    public void test_change_permissions_accepts_script_permissions() {
        final var req = new ChangePermissionsRequest();
        req.setUsername("user");
        req.setScriptPermissions(Map.of("mydb", ScriptPermissionLevel.NONE));
        assertTrue(RequestValidator.validate(req).isValid());
    }

    @Test
    public void test_change_permissions_without_script_permissions_reads_as_empty() {
        final var req = (ChangePermissionsRequest) org.ccg.mpp.ops.req.RequestParser
                .parseRequest("{\"type\":\"CHANGE_PERMISSIONS\",\"username\":\"user\"}");
        assertTrue(RequestValidator.validate(req).isValid());
        assertTrue(req.getScriptPermissions().isEmpty());
        assertTrue(req.getRawScriptPermissions().entrySet().isEmpty());
    }
}
