package org.ccg.mpp.unit.data.admin;

import static org.ccg.mpp.test.EqualityChecks.assertDiffers;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.ccg.mpp.data.admin.AdminUserEntry;
import org.ccg.mpp.data.auth.GlobalPermissionType;
import org.ccg.mpp.data.auth.PermissionLevel;
import org.junit.jupiter.api.Test;

public class AdminUserEntryCoverageTest {

    private static AdminUserEntry user(String name) {
        final Map<String, PermissionLevel> projectPerms = new HashMap<>();
        projectPerms.put("project1", PermissionLevel.READ_WRITE);
        return new AdminUserEntry(name, "h", false, Set.of(GlobalPermissionType.CREATE_PROJECT), projectPerms,
                new HashMap<>());
    }

    @Test
    public void test_equals_and_hashcode() {
        final var a = user("bob");
        final var b = user("bob");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals(a, a);
        assertDiffers(a, null);
        assertDiffers(a, "not-a-user");
        assertNotEquals(a, user("carol"));
    }

    @Test
    public void test_to_response_json_includes_permissions_and_owned_projects() {
        final var json = user("bob").toResponseJson(List.of("project1", "project2"));
        assertEquals("bob", json.get("_id").asJsonString().getValue());
        assertEquals(1, json.get("globalPermissions").asJsonArray().asList().size());
        assertEquals("READ_WRITE",
                json.get("projectPermissions").asJsonObject().get("project1").asJsonString().getValue());
        assertEquals(2, json.get("ownedProjects").asJsonArray().asList().size());
    }

    @Test
    public void test_setters_rebuild_data_and_getters() {
        final var user = user("bob");
        user.setAdmin(true);
        user.setGlobalPermissions(Set.of(GlobalPermissionType.DROP_PROJECT));
        assertTrue(user.isAdmin());
        assertEquals(Set.of(GlobalPermissionType.DROP_PROJECT), user.getGlobalPermissions());
        assertEquals(PermissionLevel.READ_WRITE, user.getProjectPermissions().get("project1"));
        assertEquals("h", user.getPasswordHash());
        assertTrue(user.toString().contains("bob"));
    }
}
