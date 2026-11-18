package org.ccg.mpp.cluster.admin;

import java.util.ArrayList;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.cluster.ClusterConfig;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.admin.AdminDbEntry;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.log.Logger;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.OnDiskNameRegistry;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.admin.AdminRecordKey;
import org.ccg.mpp.ops.admin.AdminStamp;
import org.ccg.mpp.ops.admin.DatabaseOperationHelper;
import org.ccg.mpp.ops.admin.GrantPruner;
import org.ccg.mpp.ops.admin.GrantRewrite;
import org.ccg.mpp.ops.admin.LeftoverFolders;

final class DatabaseRecordApplier {
    private static final Logger logger = Logger.logFor(DatabaseRecordApplier.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private static final AdminQuarantine quarantine = new AdminQuarantine();

    private DatabaseRecordApplier() {
    }

    static boolean install(AdminRecord record) throws Exception {
        final var dbName = record.key().dbName();
        final var waitMillis = clusterConfig.replicationAckTimeoutMs();
        if (!locks.tryLockDatabaseExclusive(dbName, waitMillis)) {
            logger.warning(skipping(dbName, "it stayed locked"));
            return false;
        }
        try {
            return installUnderNamesLock(record, waitMillis);
        } finally {
            locks.releaseDatabaseExclusive(dbName);
        }
    }

    private static boolean installUnderNamesLock(AdminRecord record, long waitMillis) throws Exception {
        final var dbName = record.key().dbName();
        if (!locks.tryLockWrite(Globals.ADMIN_DB_NAME, Globals.DATABASE_NAMES_LOCK, waitMillis)) {
            logger.warning(skipping(dbName, "the database names lock stayed held"));
            return false;
        }
        try {
            final var sibling = caseVariantOf(dbName);
            if (sibling != null) {
                logger.warning("Skipping the install of database " + dbName + ": " + sibling.dbName()
                        + " was registered under the same folder meanwhile. The next round settles the pair by"
                        + " version.");
                return false;
            }
            return installUnderLocks(record);
        } finally {
            locks.release(Globals.ADMIN_DB_NAME, Globals.DATABASE_NAMES_LOCK);
        }
    }

    private static boolean installUnderLocks(AdminRecord record) throws Exception {
        if (!AdminRecords.stillOutranks(record)) {
            return true;
        }
        final var dbName = record.key().dbName();
        final var local = cache.getAdminDbEntry(dbName);
        if (local == null && LeftoverFolders.leftoverDatabaseStays(dbName)) {
            return false;
        }
        fs.createDatabaseFolder(dbName);
        final var owners = AdminDbEntry.fromJsonObject(record.body()).getOwners();
        final var installed = new AdminDbEntry(dbName,
                local == null ? new ArrayList<>() : new ArrayList<>(local.getCollections()), new ArrayList<>(owners));
        installed.setVersion(record.version());
        AdminOperationHelper.saveDatabaseEntry(installed);
        return true;
    }

    static AdminRecordKey caseVariantOf(String dbName) {
        final var sibling = OnDiskNameRegistry.collidingDatabase(dbName);
        if (sibling == null || cache.getAdminDbEntry(sibling) == null) {
            return null;
        }
        return AdminRecordKey.database(sibling);
    }

    static boolean remove(AdminRecord tombstone, boolean live) throws Exception {
        final var key = tombstone.key();
        final var dbName = key.dbName();
        AdminStamp.tombstoneAt(key, tombstone.version());
        if (cache.getAdminDbEntry(dbName) == null || !tombstone.outranks(AdminRecords.live(key))) {
            return true;
        }
        if (live) {
            return DatabaseOperationHelper.dropDatabase(dbName, true, GrantRewrite.KEEP)
                    .getStatus() == OperationStatus.OK;
        }
        if (!quarantine.dropDatabase(dbName, clusterConfig.replicationAckTimeoutMs())) {
            return false;
        }
        GrantPruner.forDroppedDatabase(dbName, GrantRewrite.KEEP);
        return true;
    }

    private static String skipping(String dbName, String reason) {
        return "Skipping the install of database " + dbName + ": " + reason + " for "
                + clusterConfig.replicationAckTimeoutMs() + "ms. The next round retries it.";
    }
}
