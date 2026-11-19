package org.ccg.mpp.unit.ops.req;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Set;
import org.ccg.mpp.data.auth.GlobalPermissionType;
import org.ccg.mpp.data.auth.PermissionLevel;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.req.ChangePermissionsRequest;
import org.ccg.mpp.ops.req.DeleteUserRequest;
import org.junit.jupiter.api.Test;

public class UserRequestTypesTest {
    @Test
    public void test_delete_user_request_type() {
        final var req = new DeleteUserRequest();
        assertEquals(OperationType.DELETE_USER, req.getType());
    }

    @Test
    public void test_delete_user_username() {
        final var req = new DeleteUserRequest();
        req.setUsername("user123");
        assertEquals("user123", req.getUsername());
    }

    @Test
    public void test_change_permissions_request_type() {
        final var req = new ChangePermissionsRequest();
        assertEquals(OperationType.CHANGE_PERMISSIONS, req.getType());
    }

    @Test
    public void test_change_permissions_admin_default_false() {
        final var req = new ChangePermissionsRequest();
        assertFalse(req.getAdmin());
    }

    @Test
    public void test_change_permissions_admin_setter() {
        final var req = new ChangePermissionsRequest();
        req.setAdmin(true);
        assertTrue(req.getAdmin());
    }

    @Test
    public void test_change_permissions_all_fields() {
        final var req = new ChangePermissionsRequest();
        req.setUsername("user");
        req.setAdmin(true);

        final var globalPerms = Set.of(GlobalPermissionType.DROP_PROJECT);
        req.setGlobalPermissions(globalPerms);

        final var projectPerms = Map.of("project1", PermissionLevel.READ_WRITE);
        req.setProjectPermissions(projectPerms);

        final var collPerms = Map.of("project1|coll", PermissionLevel.READ);
        req.setCollectionPermissions(collPerms);

        assertEquals("user", req.getUsername());
        assertTrue(req.getAdmin());
        assertEquals(globalPerms, req.getGlobalPermissions());
        assertEquals(projectPerms, req.getProjectPermissions());
        assertEquals(collPerms, req.getCollectionPermissions());
    }

    @Test
    public void test_delete_user_null_username_getter() {
        final var req = new DeleteUserRequest();
        assertNull(req.getUsername());
    }
}
