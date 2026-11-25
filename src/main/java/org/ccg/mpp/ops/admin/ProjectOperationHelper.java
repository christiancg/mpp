package org.ccg.mpp.ops.admin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.ccg.mpp.bckg_ops.ScheduleRegistry;
import org.ccg.mpp.bckg_ops.TriggerExecutor;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.cluster.HybridClock;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.ConfigKey;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.conn.ClientTracker;
import org.ccg.mpp.data.admin.AdminProjectEntry;
import org.ccg.mpp.ex.CollectionBusyException;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.listen.ListenManager;
import org.ccg.mpp.log.Logger;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.CompiledProcedureCache;
import org.ccg.mpp.ops.DroppedCollections;
import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.OnDiskNameRegistry;
import org.ccg.mpp.ops.OperationLocks;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.TriggerRunLog;
import org.ccg.mpp.ops.req.CreateProjectRequest;
import org.ccg.mpp.ops.req.DropProjectRequest;
import org.ccg.mpp.ops.req.SetProjectOwnersRequest;
import org.ccg.mpp.ops.resp.ListProjectsResponse;
import org.ccg.mpp.ops.resp.OperationResponse;

public final class ProjectOperationHelper {
    private static final Logger logger = Logger.logFor(ProjectOperationHelper.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final HybridClock hybridClock = IocContainer.get(HybridClock.class);
    @SuppressWarnings("FieldMayBeFinal")
    private static ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private static final ListenManager listenManager = IocContainer.get(ListenManager.class);
    private static final CompiledProcedureCache compiledProcedures = IocContainer.get(CompiledProcedureCache.class);
    private static final ScheduleRegistry scheduleRegistry = IocContainer.get(ScheduleRegistry.class);
    private static final TriggerExecutor triggerExecutor = IocContainer.get(TriggerExecutor.class);
    private static final Configuration configuration = Configuration.getInstance();

    private ProjectOperationHelper() {
    }

    public static OperationResponse processCreateProjectOperation(CreateProjectRequest createProjectRequest,
            UUID clientId) {
        final var projectName = createProjectRequest.getProjectName();
        return OperationResponse.respondOrError(OperationType.CREATE_PROJECT, ErrorCode.ERROR_CREATING_PROJECT, () -> {
            if (!locks.tryLockProjectExclusive(projectName, OperationLocks.lockBudgetMillis(false))) {
                return new OperationResponse(OperationType.CREATE_PROJECT, ErrorCode.TRANSACTION_LOCK_TIMEOUT);
            }
            try {
                return createUnderProjectBarrier(createProjectRequest, clientId);
            } finally {
                locks.releaseProjectExclusive(projectName);
            }
        });
    }

    private static OperationResponse createUnderProjectBarrier(CreateProjectRequest createProjectRequest, UUID clientId)
            throws Exception {
        final var projectName = createProjectRequest.getProjectName();
        if (cache.getAdminProjectEntry(projectName) != null) {
            return new OperationResponse(OperationType.CREATE_PROJECT, ErrorCode.PROJECT_ALREADY_EXISTS);
        }
        return registerUnderNameLock(createProjectRequest, clientId);
    }

    private static OperationResponse registerUnderNameLock(CreateProjectRequest createProjectRequest, UUID clientId)
            throws Exception {
        if (!locks.tryLockWrite(Globals.ADMIN_PROJECT_NAME, Globals.PROJECT_NAMES_LOCK,
                OperationLocks.lockBudgetMillis(false))) {
            return new OperationResponse(OperationType.CREATE_PROJECT, ErrorCode.TRANSACTION_LOCK_TIMEOUT);
        }
        try {
            final var colliding = OnDiskNameRegistry.collidingProject(createProjectRequest.getProjectName());
            if (colliding != null) {
                return new OperationResponse(OperationType.CREATE_PROJECT, ErrorCode.NAME_COLLIDES_ON_DISK, colliding);
            }
            final var maxProjects = configuration.intValue(ConfigKey.MAX_PROJECTS);
            if (cache.getUserProjectNames().size() >= maxProjects) {
                return new OperationResponse(OperationType.CREATE_PROJECT,
                        ErrorCode.TOO_MANY_PROJECTS.getDefaultMessage() + " (" + maxProjects + ")",
                        ErrorCode.TOO_MANY_PROJECTS);
            }
            return register(createProjectRequest, clientId);
        } finally {
            locks.release(Globals.ADMIN_PROJECT_NAME, Globals.PROJECT_NAMES_LOCK);
        }
    }

    private static OperationResponse register(CreateProjectRequest createProjectRequest, UUID clientId)
            throws Exception {
        final var projectName = createProjectRequest.getProjectName();
        if (LeftoverFolders.leftoverProjectStays(projectName)) {
            return new OperationResponse(OperationType.CREATE_PROJECT, ErrorCode.ERROR_CREATING_PROJECT);
        }
        if (fs.createProjectFolder(projectName)) {
            final var username = clientTracker.getAuthenticatedUsername(clientId);
            return AdminOperationHelper.withUsersLock(() -> saveNewProjectEntry(projectName, existingUser(username)));
        }
        return new OperationResponse(OperationType.CREATE_PROJECT, ErrorCode.PROJECT_ALREADY_EXISTS);
    }

    private static String existingUser(String username) {
        return username != null && cache.getAdminUserEntry(username) != null ? username : null;
    }

    private static OperationResponse saveNewProjectEntry(String projectName, String owner)
            throws IOException, InterruptedException {
        final var owners = owner != null ? List.of(owner) : List.<String>of();
        AdminOperationHelper.saveProjectEntry(
                new AdminProjectEntry(projectName, new ArrayList<>(), new ArrayList<>(owners), hybridClock.next()));
        return OperationResponse.ok(OperationType.CREATE_PROJECT, "Project created successfully");
    }

    public static OperationResponse processSetProjectOwners(SetProjectOwnersRequest request) {
        final var projectName = request.getProjectName();
        return OperationResponse.respondOrError(OperationType.SET_PROJECT_OWNERS,
                ErrorCode.ERROR_UPDATING_PROJECT_OWNERS, () -> {
                    if (cache.getAdminProjectEntry(projectName) == null) {
                        return projectNotFound(projectName);
                    }
                    return OperationLocks.withProjectShared(projectName, OperationType.SET_PROJECT_OWNERS,
                            ErrorCode.ERROR_UPDATING_PROJECT_OWNERS, () -> AdminOperationHelper.withUsersLock(
                                    () -> updateOwnersOfExistingUsers(projectName, request.getOwners())));
                });
    }

    private static OperationResponse updateOwnersUnderProjectBarrier(String projectName, List<String> owners)
            throws IOException, InterruptedException {
        if (!AdminOperationHelper.updateProjectOwners(projectName, owners)) {
            return projectNotFound(projectName);
        }
        return OperationResponse.ok(OperationType.SET_PROJECT_OWNERS, "Project owners updated successfully");
    }

    private static OperationResponse updateOwnersOfExistingUsers(String projectName, List<String> owners)
            throws IOException, InterruptedException {
        if (cache.getAdminProjectEntry(projectName) == null) {
            return projectNotFound(projectName);
        }
        final var missing = owners.stream().filter(owner -> cache.getAdminUserEntry(owner) == null).findFirst();
        if (missing.isPresent()) {
            return new OperationResponse(OperationType.SET_PROJECT_OWNERS,
                    "user '" + missing.get() + "' does not exist", ErrorCode.USER_NOT_FOUND);
        }
        return updateOwnersUnderProjectBarrier(projectName, owners);
    }

    private static OperationResponse projectNotFound(String projectName) {
        return new OperationResponse(OperationType.SET_PROJECT_OWNERS, "Project '" + projectName + "' not found",
                ErrorCode.PROJECT_NOT_FOUND);
    }

    public static OperationResponse processDropProjectOperation(DropProjectRequest dropProjectRequest,
            RestampedRecords restamped) {
        final var projectName = dropProjectRequest.getProjectName();
        final var dropped = dropProject(projectName, false, new GrantRewrite.Restamp(restamped));
        if (dropped.getStatus() == OperationStatus.OK) {
            return OperationResponse.respondOrError(OperationType.DROP_PROJECT, ErrorCode.ERROR_DROPPING_PROJECT,
                    () -> {
                        AdminStamp.tombstone(AdminRecordKey.project(projectName));
                        return dropped;
                    });
        }
        return dropped;
    }

    public static OperationResponse dropProject(String projectName, boolean bounded, GrantRewrite grantRewrite) {
        final var deadline = System.currentTimeMillis() + OperationLocks.lockBudgetMillis(bounded);
        var barrierHeld = false;
        try {
            if (!locks.tryLockProjectExclusive(projectName, remainingUntil(deadline))) {
                return new OperationResponse(OperationType.DROP_PROJECT, ErrorCode.TRANSACTION_LOCK_TIMEOUT);
            }
            barrierHeld = true;
            final var dbEntry = cache.getAdminProjectEntry(projectName);
            if (dbEntry == null) {
                return new OperationResponse(OperationType.DROP_PROJECT, ErrorCode.ERROR_DROPPING_PROJECT);
            }
            final var collNames = dbEntry.getCollections();
            return locks.withRetiringCollectionLocks(projectName, collNames, remainingUntil(deadline),
                    () -> deleteProjectUnderLocks(projectName, collNames, remainingUntil(deadline), grantRewrite));
        } catch (CollectionBusyException busy) {
            return new OperationResponse(OperationType.DROP_PROJECT, ErrorCode.TRANSACTION_LOCK_TIMEOUT);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return dropFailed(exception);
        } catch (Exception exception) {
            return dropFailed(exception);
        } finally {
            if (barrierHeld) {
                locks.removeLock(projectName, Globals.COLLECTION_NAMES_LOCK);
                locks.releaseProjectExclusive(projectName);
                locks.removeProjectLock(projectName);
            }
        }
    }

    private static OperationResponse deleteProjectUnderLocks(String projectName, List<String> collNames,
            long fieldLockBudget, GrantRewrite grantRewrite) throws Exception {
        if (!FieldIndexDropFence.holdingFieldIndexLocks(projectName, collNames, fieldLockBudget,
                () -> fs.deleteProject(projectName))) {
            return new OperationResponse(OperationType.DROP_PROJECT, ErrorCode.ERROR_DROPPING_PROJECT);
        }
        ProjectFilesRetirement.drop(projectName);
        DroppedCollections.forgetProject(projectName);
        AdminOperationHelper.deleteProjectEntry(projectName);
        compiledProcedures.invalidateProject(projectName);
        scheduleRegistry.removeProject(projectName);
        listenManager.endAllForProject(projectName, ListenManager.PROJECT_DROPPED);
        triggerExecutor.discardQueuedFor(projectName, null);
        TriggerRunLog.discardAllFor(projectName, null);
        GrantPruner.forDroppedProject(projectName, grantRewrite);
        return OperationResponse.ok(OperationType.DROP_PROJECT, "Project dropped successfully");
    }

    private static OperationResponse dropFailed(Exception exception) {
        logger.error(OperationType.DROP_PROJECT + " failed with " + ErrorCode.ERROR_DROPPING_PROJECT.getCode(),
                exception);
        return new OperationResponse(OperationType.DROP_PROJECT, ErrorCode.ERROR_DROPPING_PROJECT);
    }

    private static long remainingUntil(long deadline) {
        return Math.max(0, deadline - System.currentTimeMillis());
    }

    public static OperationResponse processListProjectsOperation(String actingUser) {
        return OperationResponse.respondOrError(OperationType.LIST_PROJECTS, ErrorCode.ERROR_LISTING_PROJECTS, () -> {
            final var user = actingUser == null ? null : cache.getAdminUserEntry(actingUser);
            final var names = cache.getUserProjectNames().stream()
                    .filter(name -> ProjectVisibility.isVisible(user, cache.getAdminProjectEntry(name))).toList();
            return new ListProjectsResponse("Ok", names);
        });
    }
}
