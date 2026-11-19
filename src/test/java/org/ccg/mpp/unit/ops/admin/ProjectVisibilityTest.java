package org.ccg.mpp.unit.ops.admin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.ccg.mpp.data.admin.AdminProjectEntry;
import org.ccg.mpp.data.admin.AdminUserEntry;
import org.ccg.mpp.data.auth.PermissionLevel;
import org.ccg.mpp.data.auth.ScriptPermissionLevel;
import org.ccg.mpp.ops.admin.ProjectVisibility;
import org.junit.jupiter.api.Test;

public class ProjectVisibilityTest {
    private static final AdminProjectEntry PROJECT = new AdminProjectEntry("db", new ArrayList<>(), new ArrayList<>());

    private static AdminUserEntry user(boolean admin, Map<String, PermissionLevel> projects,
            Map<String, PermissionLevel> collections, Map<String, ScriptPermissionLevel> scripts) {
        return new AdminUserEntry("viewer", "hash", admin, Set.of(), new HashMap<>(projects),
                new HashMap<>(collections), new HashMap<>(scripts));
    }

    private static AdminUserEntry noGrants() {
        return user(false, Map.of(), Map.of(), Map.of());
    }

    @Test
    public void admin_sees_all() {
        assertTrue(ProjectVisibility.isVisible(user(true, Map.of(), Map.of(), Map.of()), PROJECT));
    }

    @Test
    public void owner_sees_owned_project() {
        final var owned = new AdminProjectEntry("db", new ArrayList<>(), new ArrayList<>(List.of("viewer")));
        assertTrue(ProjectVisibility.isVisible(noGrants(), owned));
    }

    @Test
    public void project_permission_lists_it() {
        assertTrue(ProjectVisibility.isVisible(user(false, Map.of("db", PermissionLevel.READ), Map.of(), Map.of()),
                PROJECT));
    }

    @Test
    public void collection_permission_lists_only_its_project() {
        final var viewer = user(false, Map.of(), Map.of("db|coll", PermissionLevel.READ), Map.of());
        assertTrue(ProjectVisibility.isVisible(viewer, PROJECT));
        assertFalse(ProjectVisibility.isVisible(viewer, new AdminProjectEntry("other")));
    }

    @Test
    public void script_permission_run_or_manage_lists_it_but_none_does_not() {
        assertTrue(ProjectVisibility.isVisible(user(false, Map.of(), Map.of(), Map.of("db", ScriptPermissionLevel.RUN)),
                PROJECT));
        assertTrue(ProjectVisibility
                .isVisible(user(false, Map.of(), Map.of(), Map.of("db", ScriptPermissionLevel.MANAGE)), PROJECT));
        assertFalse(ProjectVisibility
                .isVisible(user(false, Map.of(), Map.of(), Map.of("db", ScriptPermissionLevel.NONE)), PROJECT));
    }

    @Test
    public void no_grant_lists_nothing() {
        assertFalse(ProjectVisibility.isVisible(noGrants(), PROJECT));
    }

    @Test
    public void collection_key_prefix_does_not_match_a_project_with_the_same_leading_characters() {
        final var viewer = user(false, Map.of("project2", PermissionLevel.READ),
                Map.of("project2|c", PermissionLevel.READ), Map.of("project2", ScriptPermissionLevel.RUN));
        assertFalse(ProjectVisibility.isVisible(viewer, PROJECT));
        assertTrue(ProjectVisibility.isVisible(viewer, new AdminProjectEntry("project2")));
    }

    @Test
    public void null_user_sees_nothing() {
        assertFalse(ProjectVisibility.isVisible(null, PROJECT));
        assertFalse(ProjectVisibility.isVisible(noGrants(), null));
    }
}
