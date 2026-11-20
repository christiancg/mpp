package org.ccg.mpp.ops.admin;

import java.util.List;
import org.ccg.mpp.bckg_ops.TriggerExecutor;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.ConfigKey;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.admin.AdminCollEntry;
import org.ccg.mpp.ex.CollectionBusyException;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.listen.ListenManager;
import org.ccg.mpp.log.Logger;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.DroppedCollections;
import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.OnDiskNameRegistry;
import org.ccg.mpp.ops.OperationLocks;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.TriggerRunLog;
import org.ccg.mpp.ops.req.CreateCollectionRequest;
import org.ccg.mpp.ops.req.DropCollectionRequest;
import org.ccg.mpp.ops.req.ListCollectionsRequest;
import org.ccg.mpp.ops.resp.ListCollectionsResponse;
import org.ccg.mpp.ops.resp.OperationResponse;

public final class CollectionOperationHelper {
    private static final Logger logger = Logger.logFor(CollectionOperationHelper.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final ListenManager listenManager = IocContainer.get(ListenManager.class);
    private static final TriggerExecutor triggerExecutor = IocContainer.get(TriggerExecutor.class);
    private static final Configuration configuration = Configuration.getInstance();
    private static final org.ccg.mpp.cluster.HybridClock hybridClock = IocContainer
            .get(org.ccg.mpp.cluster.HybridClock.class);

    private CollectionOperationHelper() {
    }

    public static OperationResponse processCreateCollectionOperation(CreateCollectionRequest createCollectionRequest) {
        final var projectName = createCollectionRequest.getProjectName();
        return OperationLocks.withProjectShared(projectName, OperationType.CREATE_COLLECTION,
                ErrorCode.ERROR_CREATING_COLLECTION, () -> createUnderProjectBarrier(createCollectionRequest));
    }

    private static OperationResponse createUnderProjectBarrier(CreateCollectionRequest createCollectionRequest) {
        final var projectName = createCollectionRequest.getProjectName();
        final var collName = createCollectionRequest.getCollectionName();
        return OperationLocks.withCollectionLock(projectName, collName, OperationType.CREATE_COLLECTION,
                ErrorCode.ERROR_CREATING_COLLECTION, () -> {
                    if (cache.getAdminProjectEntry(projectName) == null) {
                        return new OperationResponse(OperationType.CREATE_COLLECTION, ErrorCode.PROJECT_NOT_FOUND);
                    }
                    fs.createProjectFolder(projectName);
                    return registerUnderNameLock(createCollectionRequest);
                });
    }

    private static OperationResponse registerUnderNameLock(CreateCollectionRequest createCollectionRequest)
            throws Exception {
        final var projectName = createCollectionRequest.getProjectName();
        if (!locks.tryLockWrite(projectName, Globals.COLLECTION_NAMES_LOCK, OperationLocks.lockBudgetMillis(false))) {
            return new OperationResponse(OperationType.CREATE_COLLECTION, ErrorCode.TRANSACTION_LOCK_TIMEOUT);
        }
        try {
            final var colliding = OnDiskNameRegistry.collidingCollection(projectName,
                    createCollectionRequest.getCollectionName());
            if (colliding != null) {
                return new OperationResponse(OperationType.CREATE_COLLECTION, ErrorCode.NAME_COLLIDES_ON_DISK,
                        colliding);
            }
            final var maxCollections = configuration.intValue(ConfigKey.MAX_COLLECTIONS_PER_PROJECT);
            if (isNotRegistered(projectName, createCollectionRequest.getCollectionName())
                    && cache.getCollectionNamesForProject(projectName).size() >= maxCollections) {
                return new OperationResponse(OperationType.CREATE_COLLECTION,
                        ErrorCode.TOO_MANY_COLLECTIONS_FOR_PROJECT.getDefaultMessage() + " (" + maxCollections + ")",
                        ErrorCode.TOO_MANY_COLLECTIONS_FOR_PROJECT);
            }
            return register(createCollectionRequest);
        } finally {
            locks.release(projectName, Globals.COLLECTION_NAMES_LOCK);
        }
    }

    private static OperationResponse register(CreateCollectionRequest createCollectionRequest) throws Exception {
        final var projectName = createCollectionRequest.getProjectName();
        final var collName = createCollectionRequest.getCollectionName();
        final var result = LeftoverFolders.moveAsideUnregisteredCollection(projectName, collName)
                && fs.createCollectionFile(projectName, collName);
        if (!result) {
            return new OperationResponse(OperationType.CREATE_COLLECTION, ErrorCode.ERROR_CREATING_COLLECTION);
        }
        if (AdminOperationHelper.getCollectionEntry(projectName, collName) != null) {
            return OperationResponse.ok(OperationType.CREATE_COLLECTION, "Collection created successfully");
        }
        AdminOperationHelper.createPageCollections(projectName, collName);
        final var entry = new AdminCollEntry(projectName, collName);
        entry.setIncarnation(hybridClock.next());
        AdminOperationHelper.saveCollectionEntry(entry);
        return OperationResponse.ok(OperationType.CREATE_COLLECTION, "Collection created successfully");
    }

    private static boolean isNotRegistered(String projectName, String collName) {
        return cache.getAdminCollectionEntry(projectName, collName) == null;
    }

    public static OperationResponse processDropCollectionOperation(DropCollectionRequest dropCollectionRequest,
            RestampedRecords restamped) {
        final var projectName = dropCollectionRequest.getProjectName();
        final var collName = dropCollectionRequest.getCollectionName();
        final var dropped = dropCollection(projectName, collName, false, new GrantRewrite.Restamp(restamped));
        if (dropped.getStatus() == OperationStatus.OK) {
            return OperationResponse.respondOrError(OperationType.DROP_COLLECTION, ErrorCode.ERROR_DROPPING_COLLECTION,
                    () -> {
                        AdminStamp.tombstone(AdminRecordKey.collection(projectName, collName));
                        return dropped;
                    });
        }
        return dropped;
    }

    public static OperationResponse dropCollection(String projectName, String collName, boolean bounded,
            GrantRewrite grantRewrite) {
        final var lockBudget = OperationLocks.lockBudgetMillis(bounded);
        boolean dropSucceeded = false;
        boolean namesLocked = false;
        try {
            if (!locks.tryLockWrite(projectName, collName, lockBudget)) {
                return new OperationResponse(OperationType.DROP_COLLECTION, ErrorCode.TRANSACTION_LOCK_TIMEOUT);
            }
            namesLocked = locks.tryLockWrite(projectName, Globals.COLLECTION_NAMES_LOCK, lockBudget);
            if (!namesLocked) {
                return new OperationResponse(OperationType.DROP_COLLECTION, ErrorCode.TRANSACTION_LOCK_TIMEOUT);
            }
            if (isNotRegistered(projectName, collName)) {
                return new OperationResponse(OperationType.DROP_COLLECTION, ErrorCode.ERROR_DROPPING_COLLECTION);
            }
            final var result = FieldIndexDropFence.holdingFieldIndexLocks(projectName, List.of(collName), lockBudget,
                    () -> fs.deleteCollectionFiles(projectName, collName));
            if (result) {
                DroppedCollections.forgetCollection(projectName, collName);
                AdminOperationHelper.deleteCollectionEntry(projectName, collName);
                AdminOperationHelper.deletePageCollections(projectName, collName);
                listenManager.endAllForCollection(projectName, collName, ListenManager.COLLECTION_DROPPED);
                triggerExecutor.discardQueuedFor(projectName, collName);
                TriggerRunLog.discardAllFor(projectName, collName);
                GrantPruner.forDroppedCollection(projectName, collName, grantRewrite);
                dropSucceeded = true;
                return OperationResponse.ok(OperationType.DROP_COLLECTION, "Collection dropped successfully");
            }
            return new OperationResponse(OperationType.DROP_COLLECTION, ErrorCode.ERROR_DROPPING_COLLECTION);
        } catch (CollectionBusyException busy) {
            return new OperationResponse(OperationType.DROP_COLLECTION, ErrorCode.TRANSACTION_LOCK_TIMEOUT);
        } catch (Exception e) {
            logger.error(
                    OperationType.DROP_COLLECTION + " failed with " + ErrorCode.ERROR_DROPPING_COLLECTION.getCode(), e);
            return new OperationResponse(OperationType.DROP_COLLECTION, ErrorCode.ERROR_DROPPING_COLLECTION);
        } finally {
            if (namesLocked) {
                locks.release(projectName, Globals.COLLECTION_NAMES_LOCK);
            }
            locks.release(projectName, collName);
            if (dropSucceeded) {
                locks.removeLock(projectName, collName);
            }
        }
    }

    public static OperationResponse processListCollectionsOperation(ListCollectionsRequest request) {
        final var projectName = request.getProjectName();
        if (projectName == null || projectName.isBlank()) {
            return new OperationResponse(OperationType.LIST_COLLECTIONS, "Project name is required",
                    ErrorCode.VALIDATION_ERROR);
        }
        if (Globals.ADMIN_PROJECT_NAME.equals(projectName)) {
            return new ListCollectionsResponse("Ok", List.of());
        }
        final var lockSet = List.of(
                Cache.getCollectionIdentifier(Globals.ADMIN_PROJECT_NAME, Globals.ADMIN_COLLECTIONS_COLLECTION_NAME));
        return OperationLocks.withReadLocks(request.isDirtyRead(), lockSet, OperationType.LIST_COLLECTIONS,
                ErrorCode.ERROR_LISTING_COLLECTIONS, () -> {
                    if (cache.getAdminProjectEntry(projectName) == null) {
                        return new OperationResponse(OperationType.LIST_COLLECTIONS,
                                "Project " + projectName + " not found", ErrorCode.PROJECT_NOT_FOUND);
                    }
                    return new ListCollectionsResponse("Ok", cache.getCollectionNamesForProject(projectName));
                });
    }
}
