package org.ccg.mpp.ops.admin;

import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.admin.AdminProjectEntry;
import org.ccg.mpp.data.admin.AdminUserEntry;
import org.ccg.mpp.data.auth.ScriptPermissionLevel;

public final class ProjectVisibility {

    private ProjectVisibility() {
    }

    public static boolean isVisible(AdminUserEntry user, AdminProjectEntry project) {
        if (user == null || project == null) {
            return false;
        }
        final var name = project.get_id();
        return user.isAdmin() || project.isOwner(user.get_id()) || user.getProjectPermissions().containsKey(name)
                || hasCollectionGrant(user, name) || hasScriptGrant(user, name);
    }

    private static boolean hasCollectionGrant(AdminUserEntry user, String projectName) {
        final var prefix = projectName + Globals.COLL_IDENTIFIER_SEPARATOR;
        return user.getCollectionPermissions().keySet().stream().anyMatch(key -> key.startsWith(prefix));
    }

    private static boolean hasScriptGrant(AdminUserEntry user, String projectName) {
        final var level = user.getScriptPermissions().get(projectName);
        return level != null && level != ScriptPermissionLevel.NONE;
    }
}
