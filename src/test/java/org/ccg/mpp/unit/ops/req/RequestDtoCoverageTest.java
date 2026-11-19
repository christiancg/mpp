package org.ccg.mpp.unit.ops.req;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.ccg.mpp.data.auth.GlobalPermissionType;
import org.ccg.mpp.data.auth.PermissionLevel;
import org.ccg.mpp.ops.req.ChangePermissionsRequest;
import org.ccg.mpp.ops.req.CreateUserRequest;
import org.junit.jupiter.api.Test;

public class RequestDtoCoverageTest {

    @Test
    public void test_change_permissions_getters_round_trip() {
        final var request = new ChangePermissionsRequest();
        request.setUsername("bob");
        request.setAdmin(true);
        request.setGlobalPermissions(Set.of(GlobalPermissionType.CREATE_PROJECT));
        request.setProjectPermissions(Map.of("project1", PermissionLevel.READ_WRITE));
        request.setCollectionPermissions(Map.of("project1|c1", PermissionLevel.READ));

        assertEquals("bob", request.getUsername());
        assertTrue(request.getAdmin());
        assertEquals(Set.of(GlobalPermissionType.CREATE_PROJECT), request.getGlobalPermissions());
        assertEquals(PermissionLevel.READ_WRITE, request.getProjectPermissions().get("project1"));
        assertEquals(PermissionLevel.READ, request.getCollectionPermissions().get("project1|c1"));
    }

    @Test
    public void test_create_user_getters_round_trip() {
        final var request = new CreateUserRequest();
        request.setUsername("carol");
        request.setPassword("secret");
        request.setAdmin(false);
        request.setGlobalPermissions(Set.of(GlobalPermissionType.DROP_PROJECT));
        request.setProjectPermissions(Map.of("project2", PermissionLevel.READ));
        request.setCollectionPermissions(Map.of("project2|c2", PermissionLevel.READ_WRITE));

        assertEquals("carol", request.getUsername());
        assertEquals("secret", request.getPassword());
        assertEquals(false, request.getAdmin());
        assertEquals(Set.of(GlobalPermissionType.DROP_PROJECT), request.getGlobalPermissions());
        assertEquals(PermissionLevel.READ, request.getProjectPermissions().get("project2"));
        assertEquals(PermissionLevel.READ_WRITE, request.getCollectionPermissions().get("project2|c2"));
    }
}
