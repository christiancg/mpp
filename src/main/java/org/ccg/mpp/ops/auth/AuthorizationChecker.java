package org.ccg.mpp.ops.auth;

import java.util.Set;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.data.admin.AdminUserEntry;
import org.ccg.mpp.data.auth.GlobalPermissionType;
import org.ccg.mpp.data.auth.PermissionLevel;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.req.AggregateRequest;
import org.ccg.mpp.ops.req.ListProceduresRequest;
import org.ccg.mpp.ops.req.ListSchedulesRequest;
import org.ccg.mpp.ops.req.ListenRequest;
import org.ccg.mpp.ops.req.OperationRequest;
import org.ccg.mpp.ops.req.agg.step.JoinAggregationStep;
import org.ccg.mpp.ops.req.validations.AggregationStepValidator;

public final class AuthorizationChecker {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final Set<OperationType> ADMIN_ONLY_OPERATIONS = Set.of(OperationType.CREATE_USER,
            OperationType.DELETE_USER, OperationType.CHANGE_PERMISSIONS, OperationType.SET_PROJECT_OWNERS,
            OperationType.LIST_USERS, OperationType.RESOLVE_TRANSACTION, OperationType.LIST_TRANSACTIONS,
            OperationType.LIST_SCRIPTS, OperationType.CANCEL_SCRIPT, OperationType.LIST_TRIGGER_RUNS,
            OperationType.RESOLVE_TRIGGER_RUN, OperationType.ARM_FAULT, OperationType.AWAIT_FAULT,
            OperationType.RELEASE_FAULT);
    private static final Set<OperationType> ALWAYS_ALLOWED_OPERATIONS = Set.of(OperationType.LIST_PROJECTS,
            OperationType.GET_PROJECT_STATS, OperationType.CLOSE_CONNECTION, OperationType.SET_PASSWORD,
            OperationType.STOP_LISTEN, OperationType.START_TRANSACTION, OperationType.COMMIT_TRANSACTION,
            OperationType.ROLLBACK_TRANSACTION);
    private static final Set<OperationType> SCRIPT_MANAGEMENT_OPERATIONS = Set.of(OperationType.SAVE_PROCEDURE,
            OperationType.DELETE_PROCEDURE, OperationType.SAVE_TRIGGER, OperationType.DELETE_TRIGGER,
            OperationType.SAVE_SCHEDULE, OperationType.DELETE_SCHEDULE, OperationType.TEST_TRIGGER);
    private static final Set<OperationType> TRIGGER_OPERATIONS = Set.of(OperationType.SAVE_TRIGGER,
            OperationType.DELETE_TRIGGER, OperationType.TEST_TRIGGER);

    private AuthorizationChecker() {
    }

    public static boolean isAdminOnly(OperationType type) {
        return ADMIN_ONLY_OPERATIONS.contains(type);
    }

    private static boolean isScriptManagementOperation(OperationType type) {
        return SCRIPT_MANAGEMENT_OPERATIONS.contains(type);
    }

    public static AuthorizationResult check(OperationRequest req, AdminUserEntry user) {
        if (user == null) {
            return AuthorizationResult.deny("User not found");
        }

        if (user.isAdmin()) {
            return AuthorizationResult.allow();
        }

        final var type = req.getType();

        if (isAdminOnly(type)) {
            return AuthorizationResult.deny("action is forbidden, no permissions");
        }

        if (ALWAYS_ALLOWED_OPERATIONS.contains(type)) {
            return AuthorizationResult.allow();
        }

        if (type == OperationType.CREATE_PROJECT) {
            return user.getGlobalPermissions().contains(GlobalPermissionType.CREATE_PROJECT)
                    ? AuthorizationResult.allow()
                    : AuthorizationResult.deny("action is forbidden, no permissions");
        }

        if (type == OperationType.DROP_PROJECT) {
            final var dbEntry = cache.getAdminProjectEntry(req.getProjectName());
            if (dbEntry != null && dbEntry.isOwner(user.get_id())) {
                return AuthorizationResult.allow();
            }
            return AuthorizationResult.deny("action is forbidden, no permissions");
        }

        final var projectName = req.getProjectName();
        if (projectName == null || projectName.isBlank()) {
            return AuthorizationResult.deny("action is forbidden, no permissions");
        }

        final var dbEntry = cache.getAdminProjectEntry(projectName);
        if (dbEntry != null && dbEntry.isOwner(user.get_id())) {
            return AuthorizationResult.allow();
        }

        // Each operation the script issues is authorized on its own request, so this grant alone never
        // widens what the script may read or write.
        if (type == OperationType.RUN_SCRIPT || type == OperationType.CALL_PROCEDURE) {
            return user.canRunScripts(projectName)
                    ? AuthorizationResult.allow()
                    : AuthorizationResult.deny("action is forbidden, no permissions");
        }

        if (isScriptManagementOperation(type)) {
            return allowsScriptManagement(user, projectName, req)
                    ? AuthorizationResult.allow()
                    : AuthorizationResult.deny("action is forbidden, no permissions");
        }

        final var requiredLevel = getRequiredPermissionLevel(type);
        final var collName = type.scope() == OperationType.Scope.PROJECT ? null : req.getCollectionName();

        if (lacksCollectionAccess(user, projectName, collName, requiredLevel)) {
            return AuthorizationResult.deny("action is forbidden, no permissions");
        }

        if (req instanceof ListProceduresRequest listRequest && listRequest.isIncludeSource()
                && !user.canManageScripts(projectName)) {
            return AuthorizationResult.deny("procedure source requires script MANAGE",
                    ErrorCode.SOURCE_REQUIRES_MANAGE);
        }

        if (req instanceof ListSchedulesRequest listRequest && listRequest.isIncludeArgs()
                && !user.canManageScripts(projectName)) {
            return AuthorizationResult.deny("schedule args require script MANAGE", ErrorCode.SOURCE_REQUIRES_MANAGE);
        }

        final java.util.List<org.ccg.mpp.ops.req.agg.BaseAggregationStep> stepsToCheck;
        if (req instanceof AggregateRequest aggReq && aggReq.getAggregationSteps() != null) {
            stepsToCheck = aggReq.getAggregationSteps();
        } else if (req instanceof ListenRequest listenReq && listenReq.getAggregationSteps() != null) {
            stepsToCheck = listenReq.getAggregationSteps();
        } else {
            stepsToCheck = java.util.List.of();
        }
        for (final var step : stepsToCheck) {
            if (step instanceof JoinAggregationStep joinStep
                    && lacksCollectionAccess(user, projectName, joinStep.getJoinCollection(), PermissionLevel.READ)) {
                return AuthorizationResult.deny("action is forbidden, no permissions");
            }
        }

        // A pipeline carrying a script executes code, so it takes the same per-project grant RUN_SCRIPT
        // does; admins and owners short-circuited above.
        if (AggregationStepValidator.containsScript(stepsToCheck) && !user.canRunScripts(projectName)) {
            return AuthorizationResult.deny("action is forbidden, no permissions");
        }

        return AuthorizationResult.allow();
    }

    public static boolean mayWrite(AdminUserEntry user, String projectName, String collName) {
        if (user == null) {
            return false;
        }
        if (user.isAdmin()) {
            return true;
        }
        final var dbEntry = cache.getAdminProjectEntry(projectName);
        if (dbEntry != null && dbEntry.isOwner(user.get_id())) {
            return true;
        }
        return !lacksCollectionAccess(user, projectName, collName, PermissionLevel.READ_WRITE);
    }

    public static boolean ownsOrAdministers(AdminUserEntry user, String projectName) {
        if (user == null) {
            return false;
        }
        if (user.isAdmin()) {
            return true;
        }
        final var dbEntry = cache.getAdminProjectEntry(projectName);
        return dbEntry != null && dbEntry.isOwner(user.get_id());
    }

    public static boolean mayRunAsDefiner(AdminUserEntry user, String projectName, String collName) {
        if (user == null) {
            return false;
        }
        if (user.isAdmin()) {
            return true;
        }
        final var dbEntry = cache.getAdminProjectEntry(projectName);
        if (dbEntry != null && dbEntry.isOwner(user.get_id())) {
            return true;
        }
        return user.canRunScripts(projectName)
                && (collName == null || !lacksCollectionAccess(user, projectName, collName, PermissionLevel.READ));
    }

    public static boolean mayReadProcedureSource(AdminUserEntry user, String projectName) {
        if (user == null) {
            return false;
        }
        if (user.isAdmin() || user.canManageScripts(projectName)) {
            return true;
        }
        final var dbEntry = cache.getAdminProjectEntry(projectName);
        return dbEntry != null && dbEntry.isOwner(user.get_id());
    }

    public static boolean canReadCollection(AdminUserEntry user, String projectName, String collName) {
        if (user == null) {
            return false;
        }
        if (user.isAdmin()) {
            return true;
        }
        final var dbEntry = cache.getAdminProjectEntry(projectName);
        return dbEntry != null && dbEntry.isOwner(user.get_id())
                || !lacksCollectionAccess(user, projectName, collName, PermissionLevel.READ);
    }

    private static boolean allowsScriptManagement(AdminUserEntry user, String projectName, OperationRequest req) {
        if (!user.canManageScripts(projectName)) {
            return false;
        }
        return !TRIGGER_OPERATIONS.contains(req.getType())
                || !lacksCollectionAccess(user, projectName, req.getCollectionName(), PermissionLevel.READ_WRITE);
    }

    private static boolean lacksCollectionAccess(AdminUserEntry user, String projectName, String collName,
            PermissionLevel requiredLevel) {
        if (collName != null && !collName.isBlank()) {
            final var collPerm = user.getCollectionPermissions().get(projectName + "|" + collName);
            if (collPerm != null) {
                return !collPerm.covers(requiredLevel);
            }
        }
        final var projectPerm = user.getProjectPermissions().get(projectName);
        return projectPerm == null || !projectPerm.covers(requiredLevel);
    }

    private static PermissionLevel getRequiredPermissionLevel(OperationType type) {
        return switch (type) {
            case FIND_BY_ID, AGGREGATE, LIST_COLLECTIONS, GET_SCHEMA, LISTEN, LIST_PROCEDURES, LIST_TRIGGERS,
                    LIST_SCHEDULES, START_SNAPSHOT, READ_SNAPSHOT, RELEASE_SNAPSHOT ->
                PermissionLevel.READ;
            default -> PermissionLevel.READ_WRITE;
        };
    }
}
