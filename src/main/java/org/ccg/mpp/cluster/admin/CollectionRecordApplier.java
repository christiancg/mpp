package org.ccg.mpp.cluster.admin;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.cluster.ClusterConfig;
import org.ccg.mpp.cluster.HybridClock;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.admin.AdminCollEntry;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.log.Logger;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.IndexHelper;
import org.ccg.mpp.ops.OnDiskNameRegistry;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.admin.AdminRecordKey;
import org.ccg.mpp.ops.admin.AdminStamp;
import org.ccg.mpp.ops.admin.CollectionOperationHelper;
import org.ccg.mpp.ops.admin.GrantPruner;
import org.ccg.mpp.ops.admin.GrantRewrite;
import org.ccg.mpp.ops.admin.LeftoverFolders;
import org.ccg.mpp.ops.req.validations.NameValidations;

final class CollectionRecordApplier {
    private static final Logger logger = Logger.logFor(CollectionRecordApplier.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private static final HybridClock hybridClock = IocContainer.get(HybridClock.class);
    private static final AdminQuarantine quarantine = new AdminQuarantine();

    private CollectionRecordApplier() {
    }

    static boolean install(AdminRecord record) throws Exception {
        final var projectName = record.key().projectName();
        final var collName = record.key().name();
        final var waitMillis = clusterConfig.replicationAckTimeoutMs();
        if (!locks.tryLockProjectShared(projectName, waitMillis)) {
            logger.warning(skipping(projectName, collName, "its project stayed locked by a drop"));
            return false;
        }
        try {
            if (!locks.tryLockWrite(projectName, collName, waitMillis)) {
                logger.warning(skipping(projectName, collName, "its write lock stayed held"));
                return false;
            }
            try {
                return installUnderNamesLock(record, waitMillis);
            } finally {
                locks.release(projectName, collName);
            }
        } finally {
            locks.releaseProjectShared(projectName);
        }
    }

    private static boolean installUnderNamesLock(AdminRecord record, long waitMillis) throws Exception {
        final var projectName = record.key().projectName();
        final var collName = record.key().name();
        if (!locks.tryLockWrite(projectName, Globals.COLLECTION_NAMES_LOCK, waitMillis)) {
            logger.warning(skipping(projectName, collName, "the collection names lock stayed held"));
            return false;
        }
        try {
            final var sibling = caseVariantOf(projectName, collName);
            if (sibling != null) {
                logger.warning("Skipping the install of " + projectName + Globals.COLL_IDENTIFIER_SEPARATOR + collName
                        + ": " + sibling.name() + " was registered under the same folder meanwhile. The next round"
                        + " settles the pair by version.");
                return false;
            }
            return installUnderLocks(record, AdminCollEntry.fromJsonObject(record.body()));
        } finally {
            locks.release(projectName, Globals.COLLECTION_NAMES_LOCK);
        }
    }

    private static boolean installUnderLocks(AdminRecord record, AdminCollEntry desired) throws Exception {
        if (!AdminRecords.stillOutranks(record)) {
            return true;
        }
        final var projectName = record.key().projectName();
        final var collName = record.key().name();
        final var incarnation = desired.getIncarnation();
        if (incarnation != 0) {
            hybridClock.observe(incarnation);
        }
        if (!quarantine.quarantineStaleIncarnation(projectName, collName, incarnation,
                clusterConfig.replicationAckTimeoutMs())) {
            return false;
        }
        if (!LeftoverFolders.moveAsideUnregisteredCollection(projectName, collName)) {
            return false;
        }
        fs.createCollectionFile(projectName, collName);
        if (cache.getAdminCollectionEntry(projectName, collName) == null) {
            AdminOperationHelper.createPageCollections(projectName, collName);
        }
        final var existing = new HashSet<>(cache.getIndexesForCollection(projectName, collName));
        final var desiredIndexes = new HashSet<>(desired.getIndexes());
        final var added = new HashSet<>(desiredIndexes);
        added.removeAll(existing);
        final var removed = new HashSet<>(existing);
        removed.removeAll(desiredIndexes);
        markAll(projectName, collName, added, removed);
        final var installed = new AdminCollEntry(projectName, collName, desiredIndexes);
        installed.setIncarnation(incarnation);
        installed.setVersion(record.version());
        AdminOperationHelper.saveCollectionEntry(installed);
        for (final var field : added) {
            IndexHelper.createIndex(projectName, collName, field);
        }
        for (final var field : removed) {
            IndexHelper.dropIndex(projectName, collName, field);
        }
        clearAll(projectName, collName, added, removed);
        return true;
    }

    private static void markAll(String projectName, String collName, Set<String> added, Set<String> removed)
            throws IOException {
        for (final var field : added) {
            fs.indexMarkers().mark(projectName, collName, field);
        }
        for (final var field : removed) {
            fs.indexMarkers().mark(projectName, collName, field);
        }
    }

    private static void clearAll(String projectName, String collName, Set<String> added, Set<String> removed) {
        added.forEach(field -> fs.indexMarkers().clear(projectName, collName, field));
        removed.forEach(field -> fs.indexMarkers().clear(projectName, collName, field));
    }

    static boolean indexFieldsAreValid(AdminRecord record) {
        final AdminCollEntry entry;
        try {
            entry = AdminCollEntry.fromJsonObject(record.body());
        } catch (RuntimeException unreadable) {
            return true;
        }
        final var indexes = entry.getIndexes();
        return indexes == null || indexes.stream().allMatch(NameValidations::isIndexFieldName);
    }

    static boolean incarnationWithinSkew(AdminRecord record) {
        final long incarnation;
        try {
            incarnation = AdminCollEntry.fromJsonObject(record.body()).getIncarnation();
        } catch (RuntimeException unreadable) {
            return true;
        }
        return incarnation == 0 || hybridClock.withinSkew(incarnation, clusterConfig.maxClockSkewMs());
    }

    static AdminRecordKey caseVariantOf(String projectName, String collName) {
        final var sibling = OnDiskNameRegistry.collidingCollection(projectName, collName);
        if (sibling == null || cache.getAdminCollectionEntry(projectName, sibling) == null) {
            return null;
        }
        return AdminRecordKey.collection(projectName, sibling);
    }

    static boolean remove(AdminRecord tombstone, boolean live) throws Exception {
        final var key = tombstone.key();
        final var projectName = key.projectName();
        final var collName = key.name();
        AdminStamp.tombstoneAt(key, tombstone.version());
        if (cache.getAdminCollectionEntry(projectName, collName) == null
                || !tombstone.outranks(AdminRecords.live(key))) {
            return true;
        }
        if (live) {
            return CollectionOperationHelper.dropCollection(projectName, collName, true, GrantRewrite.KEEP)
                    .getStatus() == OperationStatus.OK;
        }
        if (!quarantine.dropCollection(projectName, collName, clusterConfig.replicationAckTimeoutMs())) {
            return false;
        }
        GrantPruner.forDroppedCollection(projectName, collName, GrantRewrite.KEEP);
        return true;
    }

    private static String skipping(String projectName, String collName, String reason) {
        return "Skipping the install of " + projectName + Globals.COLL_IDENTIFIER_SEPARATOR + collName + ": " + reason
                + " for " + clusterConfig.replicationAckTimeoutMs() + "ms. The next round retries it.";
    }
}
