package org.ccg.mpp.ops;

import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import org.ccg.mpp.bckg_ops.ScheduleRegistry;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.cluster.ownership.OwnershipManager;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.ConfigKey;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.ScheduleDefinition;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ex.InvalidCronException;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.admin.AdminRecordKey;
import org.ccg.mpp.ops.admin.AdminStamp;
import org.ccg.mpp.ops.admin.StoredDefinitions;
import org.ccg.mpp.ops.req.DeleteScheduleRequest;
import org.ccg.mpp.ops.req.ListSchedulesRequest;
import org.ccg.mpp.ops.req.SaveScheduleRequest;
import org.ccg.mpp.ops.resp.ListSchedulesResponse;
import org.ccg.mpp.ops.resp.OperationResponse;
import org.ccg.mpp.ops.resp.SaveScheduleResponse;
import org.ccg.mpp.ops.schedule.CronExpression;

public final class ScheduleOperationHelper {
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final ScheduleRegistry registry = IocContainer.get(ScheduleRegistry.class);
    private static final OwnershipManager ownershipManager = IocContainer.get(OwnershipManager.class);
    private static final Configuration configuration = Configuration.getInstance();
    private static final ReentrantLock definerQuota = new ReentrantLock();

    private ScheduleOperationHelper() {
    }

    public static String ringKey(String name) {
        return Globals.SCHEDULES_FOLDER + Globals.COLL_IDENTIFIER_SEPARATOR + name;
    }

    public static OperationResponse executeSave(SaveScheduleRequest request, String actingUser)
            throws IOException, InterruptedException {
        if (!configuration.isSchedulesEnabled()) {
            return new OperationResponse(OperationType.SAVE_SCHEDULE, ErrorCode.SCRIPTS_DISABLED);
        }
        final var projectName = request.getProjectName();
        if (cache.getAdminProjectEntry(projectName) == null) {
            return projectNotFound(projectName);
        }
        final var timingError = validateTiming(request);
        if (timingError != null) {
            return timingError;
        }
        if (cache.getProcedure(projectName, request.getProcedureName()) == null) {
            return procedureNotFound(request);
        }
        return OperationLocks.withProjectShared(projectName, OperationType.SAVE_SCHEDULE,
                ErrorCode.ERROR_SAVING_SCHEDULE, () -> saveUnderProjectBarrier(request, actingUser));
    }

    private static OperationResponse saveUnderProjectBarrier(SaveScheduleRequest request, String actingUser)
            throws IOException, InterruptedException {
        final var projectName = request.getProjectName();
        if (cache.getAdminProjectEntry(projectName) == null) {
            return projectNotFound(projectName);
        }
        locks.lock(projectName, Globals.SCHEDULES_FOLDER);
        locks.lock(projectName, Globals.PROCEDURES_FOLDER);
        try {
            if (cache.getProcedure(projectName, request.getProcedureName()) == null) {
                return procedureNotFound(request);
            }
            final var existing = cache.getSchedule(projectName, request.getName());
            if (request.conflictsWith(existing == null ? 0L : existing.getVersion())) {
                return new OperationResponse(OperationType.SAVE_SCHEDULE, ErrorCode.PROCEDURE_VERSION_CONFLICT);
            }
            final var foreign = existing == null
                    ? null
                    : ProcedureBindingGuard.foreignDefinitionRefusal(OperationType.SAVE_SCHEDULE, projectName,
                            existing.getDefiner(), actingUser);
            if (foreign != null) {
                return foreign;
            }
            final var scheduleNames = fs.metadataFiles().listScheduleNames(projectName);
            if (existing == null && scheduleNames.size() >= configuration.getScheduleMaxPerProject()) {
                return new OperationResponse(OperationType.SAVE_SCHEDULE, ErrorCode.TOO_MANY_SCHEDULES);
            }
            final var colliding = OnDiskNameRegistry.collidingDefinition(scheduleNames, request.getName());
            if (colliding != null) {
                return new OperationResponse(OperationType.SAVE_SCHEDULE, ErrorCode.NAME_COLLIDES_ON_DISK, colliding);
            }
            definerQuota.lock();
            try {
                if (takesANewDefinerSlot(existing, actingUser)
                        && registry.countDefinedBy(actingUser) >= maxSchedulesPerUser()) {
                    return new OperationResponse(OperationType.SAVE_SCHEDULE,
                            ErrorCode.TOO_MANY_SCHEDULES_FOR_USER.getDefaultMessage() + " (" + maxSchedulesPerUser()
                                    + ")",
                            ErrorCode.TOO_MANY_SCHEDULES_FOR_USER);
                }
                final var definition = newDefinition(request, existing, actingUser);
                StoredDefinitions.writeSchedule(projectName, definition.getName(), definition.toJsonObject());
                cache.putSchedule(projectName, definition);
                registry.reload(projectName);
                return new SaveScheduleResponse("Schedule saved successfully", definition.getVersion());
            } finally {
                definerQuota.unlock();
            }
        } finally {
            locks.release(projectName, Globals.PROCEDURES_FOLDER);
            locks.release(projectName, Globals.SCHEDULES_FOLDER);
        }
    }

    private static boolean takesANewDefinerSlot(ScheduleDefinition existing, String actingUser) {
        return actingUser != null && (existing == null || !actingUser.equals(existing.getDefiner()));
    }

    private static int maxSchedulesPerUser() {
        return configuration.intValue(ConfigKey.SCHEDULE_MAX_TOTAL_PER_USER);
    }

    private static OperationResponse procedureNotFound(SaveScheduleRequest request) {
        return new OperationResponse(OperationType.SAVE_SCHEDULE, "Procedure '" + request.getProcedureName()
                + "' not found in project '" + request.getProjectName() + "'", ErrorCode.PROCEDURE_NOT_FOUND);
    }

    private static OperationResponse projectNotFound(String projectName) {
        return new OperationResponse(OperationType.SAVE_SCHEDULE, "Project '" + projectName + "' not found",
                ErrorCode.PROJECT_NOT_FOUND);
    }

    private static OperationResponse validateTiming(SaveScheduleRequest request) {
        final var hasCron = request.getCron() != null && !request.getCron().isBlank();
        final var hasInterval = request.getIntervalMs() > 0;
        if (hasCron == hasInterval) {
            return invalid("exactly one of 'cron' and 'intervalMs' is required");
        }
        final var minInterval = configuration.longValue(ConfigKey.SCHEDULE_MIN_INTERVAL_MS);
        if (hasInterval && request.getIntervalMs() < minInterval) {
            return intervalTooSmall(minInterval);
        }
        if (request.getTimeoutMs() < 0) {
            return invalid("timeoutMs must not be negative");
        }
        final var maxTimeout = configuration.longValue(ConfigKey.SCHEDULE_MAX_TIMEOUT_MS);
        if (request.getTimeoutMs() > maxTimeout) {
            return new OperationResponse(OperationType.SAVE_SCHEDULE,
                    ErrorCode.SCHEDULE_TIMEOUT_TOO_LARGE.getDefaultMessage() + " (" + maxTimeout + " ms)",
                    ErrorCode.SCHEDULE_TIMEOUT_TOO_LARGE);
        }
        if (hasCron) {
            final CronExpression expression;
            try {
                expression = CronExpression.parse(request.getCron());
            } catch (InvalidCronException e) {
                return invalid(e.getMessage());
            }
            final var now = ZonedDateTime.now(ZoneId.of(configuration.getScriptTimeZone()));
            if (expression.nextAfter(now) == null) {
                return invalid("cron '" + request.getCron() + "' has no occurrence within the search horizon");
            }
            if (expression.shortestGapMillis(now) < minInterval) {
                return intervalTooSmall(minInterval);
            }
        }
        return null;
    }

    private static OperationResponse intervalTooSmall(long minInterval) {
        return new OperationResponse(OperationType.SAVE_SCHEDULE,
                ErrorCode.SCHEDULE_INTERVAL_TOO_SMALL.getDefaultMessage() + " (" + minInterval + " ms)",
                ErrorCode.SCHEDULE_INTERVAL_TOO_SMALL);
    }

    private static OperationResponse invalid(String reason) {
        return new OperationResponse(OperationType.SAVE_SCHEDULE,
                ErrorCode.INVALID_SCHEDULE.getDefaultMessage() + ": " + reason, ErrorCode.INVALID_SCHEDULE);
    }

    private static ScheduleDefinition newDefinition(SaveScheduleRequest request, ScheduleDefinition existing,
            String actingUser) {
        final var version = existing == null ? 1L : existing.getVersion() + 1;
        final var updatedAt = System.currentTimeMillis();
        final var createdAt = existing == null ? updatedAt : existing.getCreatedAt();
        return new ScheduleDefinition(request.getName(), request.getProcedureName(), request.getCron(),
                request.getIntervalMs(), request.getArgs(), request.getTimeoutMs(), request.isEnabled(), actingUser,
                request.getDescription(), version, createdAt, updatedAt, actingUser);
    }

    public static OperationResponse executeDelete(DeleteScheduleRequest request, String actingUser)
            throws IOException, InterruptedException {
        if (!configuration.isSchedulesEnabled()) {
            return new OperationResponse(OperationType.DELETE_SCHEDULE, ErrorCode.SCRIPTS_DISABLED);
        }
        final var projectName = request.getProjectName();
        if (cache.getAdminProjectEntry(projectName) == null) {
            return new OperationResponse(OperationType.DELETE_SCHEDULE, "Project '" + projectName + "' not found",
                    ErrorCode.PROJECT_NOT_FOUND);
        }
        locks.lock(projectName, Globals.SCHEDULES_FOLDER);
        try {
            final var existing = cache.getSchedule(projectName, request.getName());
            final var foreign = existing == null
                    ? null
                    : ProcedureBindingGuard.foreignDefinitionRefusal(OperationType.DELETE_SCHEDULE, projectName,
                            existing.getDefiner(), actingUser);
            if (foreign != null) {
                return foreign;
            }
            if (fs.metadataFiles().deleteSchedule(projectName, request.getName())) {
                AdminStamp.tombstone(AdminRecordKey.schedule(projectName, request.getName()));
            }
            cache.removeSchedule(projectName, request.getName());
            registry.reload(projectName);
            return OperationResponse.ok(OperationType.DELETE_SCHEDULE, "Schedule deleted successfully");
        } finally {
            locks.release(projectName, Globals.SCHEDULES_FOLDER);
        }
    }

    public static OperationResponse executeList(ListSchedulesRequest request) {
        return OperationResponse.respondOrError(OperationType.LIST_SCHEDULES, ErrorCode.ERROR_RETRIEVING,
                () -> listSchedules(request));
    }

    private static OperationResponse listSchedules(ListSchedulesRequest request) throws IOException {
        if (!configuration.isSchedulesEnabled()) {
            return new OperationResponse(OperationType.LIST_SCHEDULES, ErrorCode.SCRIPTS_DISABLED);
        }
        final var projectName = request.getProjectName();
        if (cache.getAdminProjectEntry(projectName) == null) {
            return new OperationResponse(OperationType.LIST_SCHEDULES, "Project '" + projectName + "' not found",
                    ErrorCode.PROJECT_NOT_FOUND);
        }
        final var result = new ArrayList<JsonObject>();
        for (final var name : fs.metadataFiles().listScheduleNames(projectName)) {
            final var definition = cache.getSchedule(projectName, name);
            if (definition != null) {
                result.add(summaryOf(projectName, definition, request.isIncludeArgs()));
            }
        }
        return new ListSchedulesResponse("Ok", result);
    }

    private static JsonObject summaryOf(String projectName, ScheduleDefinition definition, boolean includeArgs) {
        final var json = includeArgs ? definition.toJsonObject() : definition.toSummaryJson();
        final var entry = registry.get(projectName, definition.getName());
        if (entry != null) {
            json.addProperty("nextRunAt", entry.getNextRunAt());
        }
        final var owner = ownershipManager.ownerFor(projectName, ringKey(definition.getName()));
        if (owner != null) {
            json.addProperty("owner", owner);
        }
        return json;
    }

    public static boolean disable(String projectName, ScheduleDefinition dispatched, String reason)
            throws IOException, InterruptedException {
        final var budget = OperationLocks.lockBudgetMillis(false);
        if (!locks.tryLockProjectShared(projectName, budget)) {
            return false;
        }
        try {
            if (!locks.tryLockWrite(projectName, Globals.SCHEDULES_FOLDER, budget)) {
                return false;
            }
            try {
                final var existing = cache.getSchedule(projectName, dispatched.getName());
                if (existing == null || !existing.isEnabled() || existing.getVersion() != dispatched.getVersion()) {
                    return false;
                }
                final var disabled = existing.disabledCopy(reason, System.currentTimeMillis());
                StoredDefinitions.writeSchedule(projectName, disabled.getName(), disabled.toJsonObject());
                cache.putSchedule(projectName, disabled);
                registry.reload(projectName);
                return true;
            } finally {
                locks.release(projectName, Globals.SCHEDULES_FOLDER);
            }
        } finally {
            locks.releaseProjectShared(projectName);
        }
    }

    public static List<ScheduleDefinition> schedulesIn(String projectName) throws IOException {
        final var result = new ArrayList<ScheduleDefinition>();
        for (final var name : fs.metadataFiles().listScheduleNames(projectName)) {
            final var definition = cache.getSchedule(projectName, name);
            if (definition != null) {
                result.add(definition);
            }
        }
        return result;
    }

    public static String scheduleReferencing(String projectName, String procedureName) throws IOException {
        for (final var definition : schedulesIn(projectName)) {
            if (procedureName.equals(definition.getProcedureName())) {
                return definition.getName();
            }
        }
        return null;
    }
}
