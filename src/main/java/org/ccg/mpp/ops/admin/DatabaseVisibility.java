package org.ccg.mpp.ops.admin;

import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.admin.AdminDbEntry;
import org.ccg.mpp.data.admin.AdminUserEntry;
import org.ccg.mpp.data.auth.ScriptPermissionLevel;

public final class DatabaseVisibility {

    private DatabaseVisibility() {
    }

    public static boolean isVisible(AdminUserEntry user, AdminDbEntry database) {
        if (user == null || database == null) {
            return false;
        }
        final var name = database.get_id();
        return user.isAdmin() || database.isOwner(user.get_id()) || user.getDatabasePermissions().containsKey(name)
                || hasCollectionGrant(user, name) || hasScriptGrant(user, name);
    }

    private static boolean hasCollectionGrant(AdminUserEntry user, String databaseName) {
        final var prefix = databaseName + Globals.COLL_IDENTIFIER_SEPARATOR;
        return user.getCollectionPermissions().keySet().stream().anyMatch(key -> key.startsWith(prefix));
    }

    private static boolean hasScriptGrant(AdminUserEntry user, String databaseName) {
        final var level = user.getScriptPermissions().get(databaseName);
        return level != null && level != ScriptPermissionLevel.NONE;
    }
}
