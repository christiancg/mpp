package org.ccg.mpp.ops;

import java.io.IOException;
import org.ccg.mpp.bckg_ops.PendingIndexWrites;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.cache.MemoryManagement;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.listen.ListenManager;
import org.ccg.mpp.log.Logger;
import org.ccg.mpp.ops.admin.AdminUsageHelper;
import org.ccg.mpp.ops.admin.DeferredWrites;
import org.ccg.mpp.ops.index.IdleIndexFolds;
import org.ccg.mpp.ops.snapshot.SnapshotRegistry;

public final class DroppedCollections {
    private static final Logger logger = Logger.logFor(DroppedCollections.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);
    private static final MemoryManagement memoryManagement = IocContainer.get(MemoryManagement.class);
    private static final ListenManager listenManager = IocContainer.get(ListenManager.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final SnapshotRegistry snapshots = IocContainer.get(SnapshotRegistry.class);

    private interface UsageRowDelete {
        void run() throws IOException, InterruptedException;
    }

    private DroppedCollections() {
    }

    public static void forgetCollection(String projectName, String collName) {
        cache.evictCollection(projectName, collName);
        cache.forgetScanRefusal(projectName, collName);
        pendingIndexWrites.clearCollection(projectName, collName);
        GroupCommit.forget(projectName, collName);
        memoryManagement.forgetUsage(projectName, collName);
        SchemaValidationHelper.forgetCollection(projectName, collName);
        listenManager.forgetCollection(projectName, collName);
        DeferredWrites.forgetCollection(projectName, collName);
        IdleIndexFolds.forgetCollection(projectName, collName);
        snapshots.abortCollection(projectName, collName, "its collection " + collName + " was dropped");
        deleteUsageRows(projectName + Globals.COLL_IDENTIFIER_SEPARATOR + collName,
                () -> AdminUsageHelper.deleteUsageOfCollection(projectName, collName));
    }

    public static void forgetProject(String projectName) {
        locks.removeCollectionLock(projectName, Globals.PROCEDURES_FOLDER);
        cache.evictProject(projectName);
        cache.forgetScanRefusals(projectName);
        pendingIndexWrites.clearProject(projectName);
        GroupCommit.forgetProject(projectName);
        memoryManagement.forgetProjectUsage(projectName);
        SchemaValidationHelper.forgetProject(projectName);
        listenManager.forgetProject(projectName);
        DeferredWrites.forgetProject(projectName);
        IdleIndexFolds.forgetProject(projectName);
        snapshots.abortProject(projectName, "its project was dropped");
        TriggerDeadLetterCap.forgetProject(projectName);
        deleteUsageRows(projectName, () -> AdminUsageHelper.deleteUsageOfProject(projectName));
    }

    private static void deleteUsageRows(String name, UsageRowDelete delete) {
        try {
            delete.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warning("The usage rows of dropped " + name + " were left for the hourly cleanup", e);
        } catch (IOException | RuntimeException e) {
            logger.warning("The usage rows of dropped " + name + " were left for the hourly cleanup", e);
        }
    }
}
