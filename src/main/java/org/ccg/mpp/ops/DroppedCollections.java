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

    public static void forgetCollection(String dbName, String collName) {
        cache.evictCollection(dbName, collName);
        cache.forgetScanRefusal(dbName, collName);
        pendingIndexWrites.clearCollection(dbName, collName);
        GroupCommit.forget(dbName, collName);
        memoryManagement.forgetUsage(dbName, collName);
        SchemaValidationHelper.forgetCollection(dbName, collName);
        listenManager.forgetCollection(dbName, collName);
        DeferredWrites.forgetCollection(dbName, collName);
        IdleIndexFolds.forgetCollection(dbName, collName);
        snapshots.abortCollection(dbName, collName, "its collection " + collName + " was dropped");
        deleteUsageRows(dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName,
                () -> AdminUsageHelper.deleteUsageOfCollection(dbName, collName));
    }

    public static void forgetDatabase(String dbName) {
        locks.removeCollectionLock(dbName, Globals.PROCEDURES_FOLDER);
        cache.evictDatabase(dbName);
        cache.forgetScanRefusals(dbName);
        pendingIndexWrites.clearDatabase(dbName);
        GroupCommit.forgetDatabase(dbName);
        memoryManagement.forgetDatabaseUsage(dbName);
        SchemaValidationHelper.forgetDatabase(dbName);
        listenManager.forgetDatabase(dbName);
        DeferredWrites.forgetDatabase(dbName);
        IdleIndexFolds.forgetDatabase(dbName);
        snapshots.abortDatabase(dbName, "its database was dropped");
        TriggerDeadLetterCap.forgetDatabase(dbName);
        deleteUsageRows(dbName, () -> AdminUsageHelper.deleteUsageOfDatabase(dbName));
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
