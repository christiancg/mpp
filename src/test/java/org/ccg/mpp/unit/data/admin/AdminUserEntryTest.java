package org.ccg.mpp.unit.data.admin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.HashSet;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.admin.AdminUserEntry;
import org.ccg.mpp.data.auth.GlobalPermissionType;
import org.ccg.mpp.data.auth.PermissionLevel;
import org.ccg.mpp.data.auth.ScriptPermissionLevel;
import org.junit.jupiter.api.Test;

public class AdminUserEntryTest {
    @Test
    public void test_constructor_sets_admin_project_and_users_collection() {
        final var entry = new AdminUserEntry("test_user", "hash", false, new HashSet<>(), new HashMap<>(),
                new HashMap<>());
        assertEquals(Globals.ADMIN_PROJECT_NAME, entry.getProjectName());
        assertEquals(Globals.ADMIN_USERS_COLLECTION_NAME, entry.getCollectionName());
        assertEquals("test_user", entry.get_id());
    }

    @Test
    public void test_serialises_to_json_with_all_fields() {
        final var globalPerms = new HashSet<GlobalPermissionType>();
        globalPerms.add(GlobalPermissionType.CREATE_PROJECT);
        final var projectPerms = new HashMap<String, PermissionLevel>();
        projectPerms.put("mydb", PermissionLevel.READ_WRITE);
        final var collPerms = new HashMap<String, PermissionLevel>();
        collPerms.put("mydb|coll", PermissionLevel.READ);

        final var entry = new AdminUserEntry("test_user", "hash", true, globalPerms, projectPerms, collPerms);
        final var data = entry.getData();

        assertNotNull(data.get("_id"));
        assertNotNull(data.get("passwordHash"));
        assertTrue(data.get("admin").asJsonBoolean().getValue());
        assertNotNull(data.get("globalPermissions"));
        assertNotNull(data.get("projectPermissions"));
        assertNotNull(data.get("collectionPermissions"));
    }

    @Test
    public void test_from_json_object_round_trip() {
        final var globalPerms = new HashSet<GlobalPermissionType>();
        globalPerms.add(GlobalPermissionType.DROP_PROJECT);
        final var projectPerms = new HashMap<String, PermissionLevel>();
        projectPerms.put("mydb", PermissionLevel.READ);
        final var collPerms = new HashMap<String, PermissionLevel>();
        collPerms.put("mydb|coll", PermissionLevel.READ_WRITE);

        final var original = new AdminUserEntry("test_user", "hash123", true, globalPerms, projectPerms, collPerms);
        final var restored = AdminUserEntry.fromJsonObject(original.getData());

        assertEquals(original.get_id(), restored.get_id());
        assertEquals(original.getPasswordHash(), restored.getPasswordHash());
        assertEquals(original.isAdmin(), restored.isAdmin());
        assertEquals(original.getGlobalPermissions(), restored.getGlobalPermissions());
        assertEquals(original.getProjectPermissions(), restored.getProjectPermissions());
        assertEquals(original.getCollectionPermissions(), restored.getCollectionPermissions());
    }

    @Test
    public void test_setters_rebuild_data() {
        final var entry = new AdminUserEntry("test_user", "hash", false, new HashSet<>(), new HashMap<>(),
                new HashMap<>());
        entry.setAdmin(true);
        assertTrue(entry.isAdmin());
        assertTrue(entry.getData().get("admin").asJsonBoolean().getValue());

        final var newPerms = new HashSet<GlobalPermissionType>();
        newPerms.add(GlobalPermissionType.CREATE_PROJECT);
        entry.setGlobalPermissions(newPerms);
        assertEquals(newPerms, entry.getGlobalPermissions());
    }

    @Test
    public void test_equals_hashcode_toString() {
        final var entry1 = new AdminUserEntry("test_user", "hash", false, new HashSet<>(), new HashMap<>(),
                new HashMap<>());
        final var entry2 = new AdminUserEntry("test_user", "hash", false, new HashSet<>(), new HashMap<>(),
                new HashMap<>());

        assertEquals(entry1, entry2);
        assertEquals(entry1.hashCode(), entry2.hashCode());
        assertNotNull(entry1.toString());
        assertTrue(entry1.toString().contains("test_user"));
    }

    @Test
    public void test_script_permissions_round_trip() {
        final var scriptPerms = new HashMap<String, ScriptPermissionLevel>();
        scriptPerms.put("mydb", ScriptPermissionLevel.RUN);
        scriptPerms.put("otherdb", ScriptPermissionLevel.NONE);
        final var entry = new AdminUserEntry("test_user", "hash", false, new HashSet<>(), new HashMap<>(),
                new HashMap<>(), scriptPerms);

        assertEquals("RUN",
                entry.getData().get("scriptPermissions").asJsonObject().get("mydb").asJsonString().getValue());
        final var parsed = AdminUserEntry.fromJsonObject(entry.getData());
        assertEquals(scriptPerms, parsed.getScriptPermissions());
        assertTrue(parsed.canRunScripts("mydb"));
        assertFalse(parsed.canRunScripts("otherdb"));
        assertFalse(parsed.canRunScripts("unknowndb"));
    }

    @Test
    public void test_script_permissions_default_to_empty() {
        final var entry = new AdminUserEntry("test_user", "hash", false, new HashSet<>(), new HashMap<>(),
                new HashMap<>());
        assertTrue(entry.getScriptPermissions().isEmpty());
        assertFalse(entry.canRunScripts("mydb"));
    }

    @Test
    public void test_null_script_permissions_is_treated_as_empty() {
        final var entry = new AdminUserEntry("test_user", "hash", false, new HashSet<>(), new HashMap<>(),
                new HashMap<>(), null);
        assertTrue(entry.getScriptPermissions().isEmpty());
    }

    @Test
    public void test_from_json_object_without_script_permissions_field() {
        final var entry = new AdminUserEntry("test_user", "hash", false, new HashSet<>(), new HashMap<>(),
                new HashMap<>());
        final var legacy = entry.getData();
        legacy.remove("scriptPermissions");
        final var parsed = AdminUserEntry.fromJsonObject(legacy);
        assertTrue(parsed.getScriptPermissions().isEmpty());
        assertFalse(parsed.canRunScripts("mydb"));
    }

    @Test
    public void test_a_row_naming_only_database_permissions_is_malformed_not_permissionless() {
        final var entry = new AdminUserEntry("test_user", "hash", false, new HashSet<>(), new HashMap<>(),
                new HashMap<>());
        final var oldShape = entry.getData();
        oldShape.add("databasePermissions", oldShape.get("projectPermissions"));
        oldShape.remove("projectPermissions");
        assertThrows(RuntimeException.class, () -> AdminUserEntry.fromJsonObject(oldShape));
    }

    @Test
    public void test_a_row_round_trips_its_project_permissions() {
        final var permissions = new HashMap<String, PermissionLevel>();
        permissions.put("shop", PermissionLevel.READ_WRITE);
        final var entry = new AdminUserEntry("test_user", "hash", false, new HashSet<>(), permissions, new HashMap<>());
        assertTrue(entry.getData().get("projectPermissions").asJsonObject().has("shop"));
        assertEquals(permissions, AdminUserEntry.fromJsonObject(entry.getData()).getProjectPermissions());
    }

    @Test
    public void test_response_json_exposes_script_permissions() {
        final var scriptPerms = new HashMap<String, ScriptPermissionLevel>();
        scriptPerms.put("mydb", ScriptPermissionLevel.MANAGE);
        final var entry = new AdminUserEntry("test_user", "hash", false, new HashSet<>(), new HashMap<>(),
                new HashMap<>(), scriptPerms);
        final var response = entry.toResponseJson(java.util.List.of());
        assertEquals("MANAGE", response.get("scriptPermissions").asJsonObject().get("mydb").asJsonString().getValue());
    }

    @Test
    public void test_script_permissions_participate_in_equality() {
        final var granted = new HashMap<String, ScriptPermissionLevel>();
        granted.put("mydb", ScriptPermissionLevel.RUN);
        final var withGrant = new AdminUserEntry("test_user", "hash", false, new HashSet<>(), new HashMap<>(),
                new HashMap<>(), granted);
        final var without = new AdminUserEntry("test_user", "hash", false, new HashSet<>(), new HashMap<>(),
                new HashMap<>());
        assertNotEquals(withGrant, without);
        assertTrue(withGrant.toString().contains("scriptPermissions"));
    }
}
