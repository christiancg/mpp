package org.ccg.mpp.ops;

import java.io.IOException;
import java.util.List;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.cluster.AdminAntiEntropyService;
import org.ccg.mpp.cluster.ClusterConfig;
import org.ccg.mpp.cluster.ClusterCoordinator;
import org.ccg.mpp.cluster.WriteGuard;
import org.ccg.mpp.cluster.admin.AdminRecords;
import org.ccg.mpp.cluster.ownership.OwnershipManager;
import org.ccg.mpp.data.EntrypointDefinition;
import org.ccg.mpp.data.MiddlewareDefinition;
import org.ccg.mpp.data.ScheduleDefinition;
import org.ccg.mpp.data.TriggerDefinition;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.log.Logger;
import org.ccg.mpp.ops.admin.AdminRecordKey;
import org.ccg.mpp.ops.auth.AuthorizationChecker;

public final class DefinerAuthority {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private static final OwnershipManager ownershipManager = IocContainer.get(OwnershipManager.class);
    private static final ClusterCoordinator coordinator = IocContainer.get(ClusterCoordinator.class);
    private static final AdminAntiEntropyService adminAntiEntropyService = IocContainer
            .get(AdminAntiEntropyService.class);
    private static final Logger logger = Logger.logFor(DefinerAuthority.class);

    private DefinerAuthority() {
    }

    public static String refusal(String definer, String projectName, String collName) {
        final var user = definer == null ? null : cache.getAdminUserEntry(definer);
        if (user == null) {
            return "definer '" + definer + "' no longer exists";
        }
        if (AuthorizationChecker.mayRunAsDefiner(user, projectName, collName)) {
            return null;
        }
        final var required = collName == null
                ? "script RUN on project '" + projectName + "'"
                : "script RUN on project '" + projectName + "' and READ on collection '" + collName + "'";
        return "definer '" + definer + "' no longer holds " + required;
    }

    public static void disableTrigger(String projectName, String collName, TriggerDefinition trigger, String reason) {
        disable(AdminRecordKey.triggers(projectName, collName),
                "Trigger '" + trigger.getName() + "' on " + projectName + "|" + collName, reason,
                () -> TriggerOperationHelper.disable(projectName, collName, trigger, reason));
    }

    public static void disableSchedule(String projectName, ScheduleDefinition schedule, String reason) {
        disable(AdminRecordKey.schedule(projectName, schedule.getName()),
                "Schedule '" + schedule.getName() + "' in " + projectName, reason,
                () -> ScheduleOperationHelper.disable(projectName, schedule, reason));
    }

    public static void disableEntrypoint(String projectName, EntrypointDefinition entrypoint, String reason) {
        disable(AdminRecordKey.entrypoint(projectName, entrypoint.getName()),
                "Entrypoint '" + entrypoint.getName() + "' in " + projectName, reason,
                () -> EntrypointOperationHelper.disable(projectName, entrypoint, reason));
    }

    public static void disableMiddleware(String projectName, MiddlewareDefinition middleware, String reason) {
        disable(AdminRecordKey.middleware(projectName, middleware.getName()),
                "Middleware '" + middleware.getName() + "' in " + projectName, reason,
                () -> MiddlewareOperationHelper.disable(projectName, middleware, reason));
    }

    private interface Disabling {
        boolean run() throws IOException, InterruptedException;
    }

    private static void disable(AdminRecordKey key, String what, String reason, Disabling disabling) {
        if (!decidesDefinitions()) {
            return;
        }
        try {
            if (!disabling.run()) {
                return;
            }
            logger.warning(disabledMessage(what, reason));
            if (clusterConfig.isEnabled()) {
                coordinator.replicateAdminRecords(AdminRecords.of(List.of(key)));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            logger.warning("Could not disable " + what + " after its " + reason + ": " + e.getMessage());
        }
    }

    static String disabledMessage(String what, String reason) {
        return what + " was disabled because its " + reason + "; its owner can re-enable it";
    }

    public static boolean decidesDefinitions() {
        if (!clusterConfig.isEnabled()) {
            return true;
        }
        return ownershipManager.isAdminCoordinator() && coordinator.guardAdmin().kind() != WriteGuard.Kind.NO_QUORUM
                && adminAntiEntropyService.hasCompletedAdminSync();
    }
}
