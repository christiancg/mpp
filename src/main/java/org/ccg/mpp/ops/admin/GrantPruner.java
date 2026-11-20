package org.ccg.mpp.ops.admin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import org.ccg.mpp.data.admin.AdminProjectEntry;
import org.ccg.mpp.data.admin.AdminUserEntry;
import org.ccg.mpp.log.Logger;
import org.ccg.mpp.ops.AdminOperationHelper;

public final class GrantPruner {
    private static final Logger logger = Logger.logFor(GrantPruner.class);

    private GrantPruner() {
    }

    public static void forDroppedProject(String projectName, GrantRewrite rewrite) {
        final var collectionPrefix = projectName + "|";
        pruneGrants(projectName, rewrite, user -> withoutGrants(user, projectName::equals,
                key -> key.startsWith(collectionPrefix), projectName::equals));
    }

    public static void forDroppedCollection(String projectName, String collName, GrantRewrite rewrite) {
        final var collectionKey = projectName + "|" + collName;
        pruneGrants(projectName + "|" + collName, rewrite,
                user -> withoutGrants(user, _ -> false, collectionKey::equals, _ -> false));
    }

    public static void forDeletedUser(String username, GrantRewrite rewrite) {
        try {
            final var rewritten = AdminOperationHelper.rewriteProjects(project -> withoutOwner(project, username),
                    rewrite.version());
            if (rewrite instanceof GrantRewrite.Restamp(var into)) {
                rewritten.forEach(projectName -> into.add(AdminRecordKey.project(projectName)));
            }
        } catch (Exception failure) {
            logger.error("Could not remove deleted user '" + username + "' from the project owners; a user "
                    + "created later under that name would inherit them", failure);
        }
    }

    private static void pruneGrants(String target, GrantRewrite rewrite, UnaryOperator<AdminUserEntry> prune) {
        try {
            final var rewritten = AdminOperationHelper.rewriteUsers(prune, rewrite.version());
            if (rewrite instanceof GrantRewrite.Restamp(var into)) {
                rewritten.forEach(username -> into.add(AdminRecordKey.user(username)));
            }
        } catch (Exception failure) {
            logger.error("Could not remove the permission grants naming '" + target + "'; a project or collection "
                    + "created later under that name would inherit them", failure);
        }
    }

    private static AdminProjectEntry withoutOwner(AdminProjectEntry project, String username) {
        if (!project.isOwner(username)) {
            return null;
        }
        final var owners = new ArrayList<>(project.getOwners());
        owners.removeIf(username::equals);
        return new AdminProjectEntry(project.get_id(), new ArrayList<>(project.getCollections()), owners);
    }

    private static AdminUserEntry withoutGrants(AdminUserEntry user, Predicate<String> projectKey,
            Predicate<String> collectionKey, Predicate<String> scriptKey) {
        final var projects = new HashMap<>(user.getProjectPermissions());
        final var collections = new HashMap<>(user.getCollectionPermissions());
        final var scripts = new HashMap<>(user.getScriptPermissions());
        final var removed = removeKeys(projects, projectKey) | removeKeys(collections, collectionKey)
                | removeKeys(scripts, scriptKey);
        if (!removed) {
            return null;
        }
        return new AdminUserEntry(user.get_id(), user.getPasswordHash(), user.isAdmin(), user.getGlobalPermissions(),
                projects, collections, scripts);
    }

    private static boolean removeKeys(Map<String, ?> grants, Predicate<String> matches) {
        return grants.keySet().removeIf(matches);
    }
}
