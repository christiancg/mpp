package org.ccg.mpp.cluster.admin;

import java.util.List;
import org.ccg.mpp.bckg_ops.ScheduleRegistry;
import org.ccg.mpp.bckg_ops.TriggerExecutor;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.ex.CollectionBusyException;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.listen.ListenManager;
import org.ccg.mpp.log.Logger;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.CompiledProcedureCache;
import org.ccg.mpp.ops.DroppedCollections;
import org.ccg.mpp.ops.TriggerRunLog;
import org.ccg.mpp.ops.admin.CollectionIncarnation;
import org.ccg.mpp.ops.admin.FieldIndexDropFence;

final class AdminQuarantine {
    private enum MoveAside {
        MOVED, LEFT, BUSY
    }

    private final Logger logger = Logger.logFor(AdminQuarantine.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final ListenManager listenManager = IocContainer.get(ListenManager.class);
    private final CompiledProcedureCache compiledProcedures = IocContainer.get(CompiledProcedureCache.class);
    private final ScheduleRegistry scheduleRegistry = IocContainer.get(ScheduleRegistry.class);
    private final TriggerExecutor triggerExecutor = IocContainer.get(TriggerExecutor.class);

    boolean quarantineStaleIncarnation(String projectName, String collName, long snapshotIncarnation, long waitMillis)
            throws Exception {
        final var localEntry = cache.getAdminCollectionEntry(projectName, collName);
        if (localEntry == null || snapshotIncarnation == 0 || localEntry.getIncarnation() == 0
                || localEntry.getIncarnation() >= snapshotIncarnation) {
            return true;
        }
        final var stale = localEntry.getIncarnation();
        final var moved = fencedUnregisterAndMoveAside(projectName, collName, stale, waitMillis);
        if (moved == MoveAside.BUSY) {
            return false;
        }
        logger.warning("Quarantined collection " + projectName + Globals.COLL_IDENTIFIER_SEPARATOR + collName
                + ": its documents belong to incarnation " + stale + ", which was dropped, and the cluster has since"
                + " re-created the name as incarnation " + snapshotIncarnation + ". They were " + movedOrLeft(moved)
                + " and the collection is now empty here.");
        return true;
    }

    private MoveAside fencedUnregisterAndMoveAside(String projectName, String collName, long incarnation,
            long waitMillis) throws Exception {
        try {
            return FieldIndexDropFence.holdingFieldIndexLocks(projectName, List.of(collName), waitMillis,
                    () -> unregisterAndMoveAside(projectName, collName, incarnation)
                            ? MoveAside.MOVED
                            : MoveAside.LEFT);
        } catch (CollectionBusyException busy) {
            logger.warning("Skipping the quarantine of " + projectName + Globals.COLL_IDENTIFIER_SEPARATOR + collName
                    + ": " + busy.getMessage() + ". The next round retries it.");
            return MoveAside.BUSY;
        }
    }

    private boolean unregisterAndMoveAside(String projectName, String collName, long incarnation) throws Exception {
        AdminOperationHelper.deleteCollectionEntry(projectName, collName);
        AdminOperationHelper.deletePageCollections(projectName, collName);
        listenManager.endAllForCollection(projectName, collName, ListenManager.COLLECTION_DROPPED);
        final var moved = fs.folderQuarantine().moveCollectionAside(projectName, collName, incarnation);
        DroppedCollections.forgetCollection(projectName, collName);
        triggerExecutor.discardQueuedFor(projectName, collName);
        TriggerRunLog.discardAllFor(projectName, collName);
        return moved;
    }

    private static String movedOrLeft(boolean moved) {
        return moved ? "moved aside on disk" : "left on disk";
    }

    private static String movedOrLeft(MoveAside moved) {
        return movedOrLeft(moved == MoveAside.MOVED);
    }

    boolean dropCollection(String projectName, String collName, long waitMillis) throws Exception {
        if (!locks.tryLockWrite(projectName, collName, waitMillis)) {
            logger.warning("Skipping the quarantine of " + projectName + Globals.COLL_IDENTIFIER_SEPARATOR + collName
                    + ": its write lock stayed held for " + waitMillis + "ms. The next round retries it.");
            return false;
        }
        try {
            return dropCollectionUnderNamesLock(projectName, collName, waitMillis);
        } finally {
            locks.release(projectName, collName);
            locks.removeLock(projectName, collName);
        }
    }

    private boolean dropCollectionUnderNamesLock(String projectName, String collName, long waitMillis)
            throws Exception {
        if (!locks.tryLockWrite(projectName, Globals.COLLECTION_NAMES_LOCK, waitMillis)) {
            logger.warning("Skipping the quarantine of " + projectName + Globals.COLL_IDENTIFIER_SEPARATOR + collName
                    + ": the collection names lock stayed held for " + waitMillis + "ms. The next round retries it.");
            return false;
        }
        try {
            final var moved = fencedUnregisterAndMoveAside(projectName, collName,
                    CollectionIncarnation.current(projectName, collName), waitMillis);
            if (moved == MoveAside.BUSY) {
                return false;
            }
            logger.warning("Quarantined collection " + projectName + Globals.COLL_IDENTIFIER_SEPARATOR + collName
                    + ": the cluster dropped it while this node held it. Its documents were " + movedOrLeft(moved)
                    + " and it no longer serves reads or writes until an operator reinstates or removes it.");
            return true;
        } finally {
            locks.release(projectName, Globals.COLLECTION_NAMES_LOCK);
        }
    }

    boolean dropProject(String projectName, long waitMillis) throws Exception {
        if (!locks.tryLockProjectExclusive(projectName, waitMillis)) {
            logger.warning("Skipping the quarantine of project " + projectName + ": it stayed locked for " + waitMillis
                    + "ms. The next round retries it.");
            return false;
        }
        try {
            return quarantineProjectUnderBarrier(projectName, waitMillis);
        } finally {
            locks.releaseProjectExclusive(projectName);
            locks.removeProjectLock(projectName);
            locks.removeLock(projectName, Globals.COLLECTION_NAMES_LOCK);
        }
    }

    private boolean quarantineProjectUnderBarrier(String projectName, long waitMillis) throws Exception {
        final var dbEntry = cache.getAdminProjectEntry(projectName);
        final var collNames = dbEntry != null ? dbEntry.getCollections() : List.<String>of();
        try {
            return locks.withRetiringCollectionLocks(projectName, collNames, waitMillis,
                    () -> FieldIndexDropFence.holdingFieldIndexLocks(projectName, collNames, waitMillis,
                            () -> quarantineProjectUnderLocks(projectName)));
        } catch (CollectionBusyException busy) {
            logger.warning("Skipping the quarantine of project " + projectName + ": " + busy.getMessage()
                    + ". The next round retries it.");
            return false;
        }
    }

    private boolean quarantineProjectUnderLocks(String projectName) throws Exception {
        AdminOperationHelper.deleteProjectEntry(projectName);
        listenManager.endAllForProject(projectName, ListenManager.PROJECT_DROPPED);
        final var moved = fs.folderQuarantine().moveProjectAside(projectName);
        DroppedCollections.forgetProject(projectName);
        compiledProcedures.invalidateProject(projectName);
        scheduleRegistry.removeProject(projectName);
        triggerExecutor.discardQueuedFor(projectName, null);
        TriggerRunLog.discardAllFor(projectName, null);
        logger.warning("Quarantined project " + projectName + ": the cluster dropped it while this node held it."
                + " Its documents were " + movedOrLeft(moved) + " and it no longer serves reads or writes"
                + " until an operator reinstates or removes it.");
        return true;
    }
}
