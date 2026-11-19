package org.ccg.mpp.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.ccg.mpp.bckg_ops.events.EntityRef;
import org.ccg.mpp.bckg_ops.events.EventType;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.cache.UsageCounter;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.conn.ClientTracker;
import org.ccg.mpp.data.DbEntry;
import org.ccg.mpp.data.PkIndexEntry;
import org.ccg.mpp.data.admin.AdminCollEntry;
import org.ccg.mpp.data.admin.AdminProjectEntry;
import org.ccg.mpp.data.admin.AdminTransactionEntry;
import org.ccg.mpp.data.admin.AdminTriggerRunEntry;
import org.ccg.mpp.data.admin.AdminUserEntry;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.log.Logger;
import org.ccg.mpp.ops.admin.AdminPageHelper;
import org.ccg.mpp.ops.admin.AdminRecordStore;
import org.ccg.mpp.ops.admin.AdminResidentRows;
import org.ccg.mpp.ops.admin.AdminStamp;
import org.ccg.mpp.ops.admin.AdminUsageHelper;
import org.ccg.mpp.ops.admin.TransactionRecords;
import org.ccg.mpp.ops.admin.TriggerRunRecords;
import org.ccg.mpp.ops.resp.OperationResponse;

public final class AdminOperationHelper {
    public enum RewriteVersion {
        KEEP, RESTAMP
    }

    private AdminOperationHelper() {
    }
    @SuppressWarnings("FieldMayBeFinal")
    private static FileSystem fs = IocContainer.get(FileSystem.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private static final Logger logger = Logger.logFor(AdminOperationHelper.class);
    private static final Set<String> VERSIONED_RECORDS = Set.of(Globals.ADMIN_PROJECTS_COLLECTION_NAME,
            Globals.ADMIN_COLLECTIONS_COLLECTION_NAME, Globals.ADMIN_USERS_COLLECTION_NAME);

    private static final AdminRecordStore<AdminTransactionEntry> TRANSACTION_OPS = new AdminRecordStore<>(
            Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME, "transaction op", cache::getPkIndexTransaction,
            AdminTransactionEntry::fromJsonObject);

    private static final AdminRecordStore<AdminTriggerRunEntry> TRIGGER_RUNS = new AdminRecordStore<>(
            Globals.ADMIN_TRIGGER_RUNS_COLLECTION_NAME, "trigger run", cache::getPkIndexTriggerRun,
            AdminTriggerRunEntry::fromJsonObject);

    public static void bulkUpdateEntryCount(String projectName, String collName, EventType type,
            List<EntityRef> inserted, long incarnation) throws InterruptedException, IOException {
        AdminPageHelper.bulkUpdateEntryCount(projectName, collName, type, inserted, incarnation);
    }

    @SuppressWarnings("RedundantThrows")
    public static void updateEntryCount(String projectName, String collName, EventType type, EntityRef entity,
            long incarnation) throws InterruptedException, IOException {
        AdminPageHelper.updateEntryCount(projectName, collName, type, entity, incarnation);
    }

    public static void bookWritePathPages(String projectName, String collName, List<EntityRef> created,
            List<EntityRef> others, long incarnation) throws InterruptedException {
        AdminPageHelper.bookWritePathPages(projectName, collName, created, others, incarnation);
    }

    public static void createPageCollections(String projectName, String collName) throws IOException {
        AdminPageHelper.createPageCollections(projectName, collName);
    }

    public static void deletePageCollections(String projectName, String collName) {
        AdminPageHelper.deletePageCollections(projectName, collName);
    }

    private static void lockAdmin(String collName) throws InterruptedException {
        locks.lock(Globals.ADMIN_PROJECT_NAME, collName);
    }

    private static void releaseAdmin(String collName) {
        locks.release(Globals.ADMIN_PROJECT_NAME, collName);
    }

    // The caller owns the lock: policy differs per collection (saveCollectionEntry also holds projects).
    private static PkIndexEntry writeAdminEntry(String collName, DbEntry entry, PkIndexEntry existingPk)
            throws IOException {
        if (VERSIONED_RECORDS.contains(collName) && entry.getVersion() <= 0) {
            entry.setVersion(AdminStamp.over(versionOf(existingPk)));
        }
        final var encodedLine = entry.encodeLine();
        if (existingPk != null) {
            entry.setPage(existingPk.getPage());
            final var updatedPk = fs.updateFromCollection(entry, existingPk, encodedLine, null,
                    AdminResidentRows.of(collName), cache.pageOccupancy(Globals.ADMIN_PROJECT_NAME, collName));
            applyAdminPageDelta(collName, EventType.UPDATED, entry);
            return updatedPk;
        }
        entry.setPage(cache.selectPageForInsert(Globals.ADMIN_PROJECT_NAME, collName, encodedLine.length));
        final var pk = fs.insertIntoCollection(entry, encodedLine);
        applyAdminPageDelta(collName, EventType.CREATED, entry);
        return pk;
    }

    public static long versionOf(PkIndexEntry pk) {
        return pk == null ? 0L : pk.getVersion();
    }

    private static void applyAdminPageDelta(String collName, EventType type, DbEntry entry) {
        applyAdminPageDelta(collName, type, List.of(entry));
    }

    private static void applyAdminPageDelta(String collName, EventType type, List<DbEntry> entries) {
        AdminPageHelper.recordLandedDelta(collName, type, entries);
    }

    // The caller owns the lock and the cache eviction, which differ per collection.
    private static void eraseAdminEntry(String collName, DbEntry entry, PkIndexEntry pk)
            throws IOException, InterruptedException {
        entry.setPreviousByteSize(pk.getLength());
        entry.setPage(pk.getPage());
        fs.deleteFromCollection(pk);
        applyAdminPageDelta(collName, EventType.DELETED, entry);
    }

    public static void saveProjectEntry(AdminProjectEntry dbEntry) throws IOException, InterruptedException {
        lockAdmin(Globals.ADMIN_PROJECTS_COLLECTION_NAME);
        try {
            final var adminProjectEntry = writeAdminEntry(Globals.ADMIN_PROJECTS_COLLECTION_NAME, dbEntry,
                    cache.getPkIndexAdminProjectEntry(dbEntry.get_id()));
            cache.putAdminProjectEntry(dbEntry, adminProjectEntry);
        } finally {
            releaseAdmin(Globals.ADMIN_PROJECTS_COLLECTION_NAME);
        }
    }

    public static void deleteProjectEntry(String projectName) throws IOException, InterruptedException {
        var adminIndexPkDbEntry = cache.getPkIndexAdminProjectEntry(projectName);
        if (adminIndexPkDbEntry != null) {
            lockAdmin(Globals.ADMIN_PROJECTS_COLLECTION_NAME);
            try {
                final var adminProjectEntry = cache.getAdminProjectEntry(projectName);
                final var collectionsSnapshot = new ArrayList<>(adminProjectEntry.getCollections());
                for (var collection : collectionsSnapshot) {
                    deleteCollectionEntry(projectName, collection);
                    AdminPageHelper.deletePageCollections(projectName, collection);
                }
                final var pkAfterCollectionRemoval = cache.getPkIndexAdminProjectEntry(projectName);
                final var entryAfterCollectionRemoval = cache.getAdminProjectEntry(projectName);
                eraseAdminEntry(Globals.ADMIN_PROJECTS_COLLECTION_NAME, entryAfterCollectionRemoval,
                        pkAfterCollectionRemoval);
                cache.removeAdminProjectEntry(projectName);
            } finally {
                releaseAdmin(Globals.ADMIN_PROJECTS_COLLECTION_NAME);
            }
        }
    }

    public static boolean updateProjectOwners(String projectName, List<String> owners)
            throws IOException, InterruptedException {
        lockAdmin(Globals.ADMIN_PROJECTS_COLLECTION_NAME);
        try {
            final var dbEntry = cache.getAdminProjectEntry(projectName);
            final var pk = cache.getPkIndexAdminProjectEntry(projectName);
            if (dbEntry == null || pk == null) {
                return false;
            }
            final var updated = new AdminProjectEntry(projectName, new ArrayList<>(dbEntry.getCollections()),
                    new ArrayList<>(owners));
            cache.putAdminProjectEntry(updated, writeAdminEntry(Globals.ADMIN_PROJECTS_COLLECTION_NAME, updated, pk));
            return true;
        } finally {
            releaseAdmin(Globals.ADMIN_PROJECTS_COLLECTION_NAME);
        }
    }

    public static void saveCollectionEntry(AdminCollEntry dbEntry) throws IOException, InterruptedException {
        lockAdmin(Globals.ADMIN_PROJECTS_COLLECTION_NAME);
        try {
            lockAdmin(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME);
            final var existingCollectionPk = cache.getPkIndexAdminCollEntry(dbEntry.get_id());
            final var pkIndexEntry = writeAdminEntry(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME, dbEntry,
                    existingCollectionPk);
            cache.putAdminCollectionEntry(dbEntry, pkIndexEntry);
            try {
                listCollectionInItsProject(dbEntry);
            } catch (IOException | InterruptedException | RuntimeException failure) {
                if (existingCollectionPk == null) {
                    unregisterNewCollectionRow(dbEntry, failure);
                }
                throw failure;
            }
        } finally {
            releaseAdmin(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME);
            releaseAdmin(Globals.ADMIN_PROJECTS_COLLECTION_NAME);
        }
    }

    private static void listCollectionInItsProject(AdminCollEntry collEntry) throws IOException, InterruptedException {
        final var split = collEntry.get_id().split(Globals.COLL_IDENTIFIER_SEPARATOR_REGEX);
        final var current = cache.getAdminProjectEntry(split[0]);
        if (current.getCollections().contains(split[1])) {
            return;
        }
        final var collections = new ArrayList<>(current.getCollections());
        collections.add(split[1]);
        publishProjectEntry(new AdminProjectEntry(split[0], collections, new ArrayList<>(current.getOwners())));
    }

    private static void unregisterNewCollectionRow(AdminCollEntry collEntry, Exception cause) {
        try {
            eraseAdminEntry(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME, collEntry,
                    cache.getPkIndexAdminCollEntry(collEntry.get_id()));
            cache.removeAdminCollEntry(collEntry.get_id());
        } catch (IOException | RuntimeException | InterruptedException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
    }

    private static void publishProjectEntry(AdminProjectEntry updated) throws IOException {
        publishProjectEntry(updated, RewriteVersion.KEEP);
    }

    private static void publishProjectEntry(AdminProjectEntry updated, RewriteVersion version) throws IOException {
        final var pk = cache.getPkIndexAdminProjectEntry(updated.get_id());
        updated.setVersion(version == RewriteVersion.KEEP ? versionOf(pk) : 0L);
        cache.putAdminProjectEntry(updated, writeAdminEntry(Globals.ADMIN_PROJECTS_COLLECTION_NAME, updated, pk));
    }

    public static void deleteCollectionEntry(String projectName, String collName)
            throws IOException, InterruptedException {
        final var collIdentifier = Cache.getCollectionIdentifier(projectName, collName);
        if (cache.getPkIndexAdminCollEntry(collIdentifier) != null) {
            // Also mutates the parent AdminProjectEntry in admin/projects; hold the projects lock too,
            // acquired before collections to match deleteProjectEntry's order.
            lockAdmin(Globals.ADMIN_PROJECTS_COLLECTION_NAME);
            try {
                lockAdmin(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME);
                eraseAdminEntry(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME,
                        cache.getAdminCollectionEntry(projectName, collName),
                        cache.getPkIndexAdminCollEntry(collIdentifier));
                cache.removeAdminCollEntry(collIdentifier);
                final var adminProjectEntry = cache.getAdminProjectEntry(projectName);
                final var remaining = new ArrayList<>(adminProjectEntry.getCollections());
                remaining.remove(collName);
                publishProjectEntry(
                        new AdminProjectEntry(projectName, remaining, new ArrayList<>(adminProjectEntry.getOwners())));
            } finally {
                releaseAdmin(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME);
                releaseAdmin(Globals.ADMIN_PROJECTS_COLLECTION_NAME);
            }
        }
    }

    public static AdminCollEntry getCollectionEntry(String projectName, String collName) {
        return cache.getAdminCollectionEntry(projectName, collName);
    }

    public static void saveNewIndex(String projectName, String collName, String fieldName)
            throws IOException, InterruptedException {
        internalUpdateAdminColl(projectName, collName, fieldName, true);
    }

    public static void deleteIndex(String projectName, String collName, String fieldName)
            throws IOException, InterruptedException {
        internalUpdateAdminColl(projectName, collName, fieldName, false);
    }

    private static void internalUpdateAdminColl(String projectName, String collName, String fieldName, boolean add)
            throws IOException, InterruptedException {
        final var collIdentifier = Cache.getCollectionIdentifier(projectName, collName);
        if (cache.getPkIndexAdminCollEntry(collIdentifier) != null) {
            lockAdmin(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME);
            try {
                final var cachedEntry = cache.getAdminCollectionEntry(projectName, collName);
                final var indexes = new HashSet<>(cachedEntry.getIndexes());
                if (add) {
                    indexes.add(fieldName);
                } else {
                    indexes.remove(fieldName);
                }
                final var adminCollEntry = new AdminCollEntry(projectName, collName, indexes);
                adminCollEntry.setIncarnation(cachedEntry.getIncarnation());
                cache.putAdminCollectionEntry(adminCollEntry, writeAdminEntry(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME,
                        adminCollEntry, cache.getPkIndexAdminCollEntry(collIdentifier)));
            } finally {
                releaseAdmin(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME);
            }
        }
    }

    public static void flushCollectionUsage(List<UsageCounter> counters) throws IOException, InterruptedException {
        AdminUsageHelper.flushCollectionUsage(counters);
    }

    public static void cleanupCollectionUsage(long maxAgeMillis) throws IOException, InterruptedException {
        AdminUsageHelper.cleanupCollectionUsage(maxAgeMillis);
    }

    public static OperationResponse withUsersLock(OperationResponse.Attempt attempt) throws Exception {
        lockAdmin(Globals.ADMIN_USERS_COLLECTION_NAME);
        try {
            return attempt.run();
        } finally {
            releaseAdmin(Globals.ADMIN_USERS_COLLECTION_NAME);
        }
    }

    public static void saveUserEntry(AdminUserEntry userEntry) throws IOException, InterruptedException {
        lockAdmin(Globals.ADMIN_USERS_COLLECTION_NAME);
        try {
            final var adminUserEntry = writeAdminEntry(Globals.ADMIN_USERS_COLLECTION_NAME, userEntry,
                    cache.getPkIndexAdminUserEntry(userEntry.get_id()));
            cache.putAdminUserEntry(userEntry, adminUserEntry);
        } finally {
            releaseAdmin(Globals.ADMIN_USERS_COLLECTION_NAME);
        }
    }

    public static List<String> rewriteUsers(UnaryOperator<AdminUserEntry> rewrite, RewriteVersion version)
            throws IOException, InterruptedException {
        lockAdmin(Globals.ADMIN_USERS_COLLECTION_NAME);
        try {
            final var rewrittenIds = new ArrayList<String>();
            for (final var user : new ArrayList<>(cache.getAllAdminUserEntries())) {
                final var rewritten = rewrite.apply(user);
                if (rewritten != null) {
                    rewritten.setVersion(version == RewriteVersion.KEEP
                            ? versionOf(cache.getPkIndexAdminUserEntry(user.get_id()))
                            : 0L);
                    saveUserEntry(rewritten);
                    rewrittenIds.add(user.get_id());
                }
            }
            return rewrittenIds;
        } finally {
            releaseAdmin(Globals.ADMIN_USERS_COLLECTION_NAME);
        }
    }

    public static List<String> rewriteProjects(UnaryOperator<AdminProjectEntry> rewrite, RewriteVersion version)
            throws IOException, InterruptedException {
        lockAdmin(Globals.ADMIN_PROJECTS_COLLECTION_NAME);
        try {
            final var rewrittenIds = new ArrayList<String>();
            for (final var project : new ArrayList<>(cache.getAllAdminProjectEntries())) {
                final var rewritten = rewrite.apply(project);
                if (rewritten != null) {
                    publishProjectEntry(rewritten, version);
                    rewrittenIds.add(project.get_id());
                }
            }
            return rewrittenIds;
        } finally {
            releaseAdmin(Globals.ADMIN_PROJECTS_COLLECTION_NAME);
        }
    }

    public static void relistUnlistedCollections() throws IOException, InterruptedException {
        rewriteProjects(AdminOperationHelper::withRegisteredCollectionsListed, RewriteVersion.KEEP);
    }

    private static AdminProjectEntry withRegisteredCollectionsListed(AdminProjectEntry project) {
        final var listed = new ArrayList<>(project.getCollections());
        final var unlisted = cache.getCollectionNamesForProject(project.get_id()).stream()
                .filter(name -> !listed.contains(name)).toList();
        if (unlisted.isEmpty()) {
            return null;
        }
        logger.warning("Re-listing " + unlisted + " in project " + project.get_id()
                + ": registered but missing from the project's list, which a process killed during"
                + " CREATE_COLLECTION leaves");
        listed.addAll(unlisted);
        return new AdminProjectEntry(project.get_id(), listed, new ArrayList<>(project.getOwners()));
    }

    public static void saveTransactionOp(AdminTransactionEntry entry) throws IOException, InterruptedException {
        lockAdmin(Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME);
        try {
            cache.putPkIndexTransaction(writeAdminEntry(Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME, entry,
                    cache.getPkIndexTransaction(entry.get_id())));
        } finally {
            releaseAdmin(Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME);
        }
        OutcomeMarkers.written(List.of(entry));
    }

    public static List<AdminTransactionEntry> readTransactionOps(List<String> opIds)
            throws IOException, InterruptedException {
        return TRANSACTION_OPS.read(opIds);
    }

    public static void deleteTransactionOps(List<String> opIds) throws IOException, InterruptedException {
        writeTransactionRecords(List.of(), opIds);
    }

    public static void applyDeferredTransactionRecords(long timeoutMillis) {
        final var collName = Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME;
        try {
            if (!locks.tryLockWrite(Globals.ADMIN_PROJECT_NAME, collName, timeoutMillis)) {
                return;
            }
            try {
                fs.applyDeferredWrites(Globals.ADMIN_PROJECT_NAME, collName);
            } finally {
                releaseAdmin(collName);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            logger.warning("Could not apply the logged transaction records an earlier write could not; retrying"
                    + " later: " + e.getMessage());
        }
    }

    public static void writeTransactionRecords(List<AdminTransactionEntry> inserts, List<String> deleteIds)
            throws IOException, InterruptedException {
        TransactionRecords.write(inserts, deleteIds);
        OutcomeMarkers.written(inserts);
    }

    public static void saveTriggerRun(AdminTriggerRunEntry entry) throws IOException, InterruptedException {
        lockAdmin(Globals.ADMIN_TRIGGER_RUNS_COLLECTION_NAME);
        try {
            final var savedPk = writeAdminEntry(Globals.ADMIN_TRIGGER_RUNS_COLLECTION_NAME, entry,
                    cache.getPkIndexTriggerRun(entry.get_id()));
            cache.putPkIndexTriggerRun(savedPk);
            TriggerRunIndex.learn(entry, savedPk);
        } finally {
            releaseAdmin(Globals.ADMIN_TRIGGER_RUNS_COLLECTION_NAME);
        }
    }

    public static void saveTriggerRuns(List<AdminTriggerRunEntry> entries) throws IOException, InterruptedException {
        saveTriggerRuns(entries, List.of());
    }

    public static void saveTriggerRuns(List<AdminTriggerRunEntry> entries, List<String> replacedRecordIds)
            throws IOException, InterruptedException {
        TriggerRunRecords.write(entries, replacedRecordIds);
    }

    public static List<AdminTriggerRunEntry> readTriggerRuns(List<String> recordIds)
            throws IOException, InterruptedException {
        return TRIGGER_RUNS.read(recordIds);
    }

    public static void deleteTriggerRuns(List<String> recordIds) throws IOException, InterruptedException {
        TriggerRunRecords.delete(recordIds);
    }

    public static void deleteUserEntry(String username) throws IOException, InterruptedException {
        lockAdmin(Globals.ADMIN_USERS_COLLECTION_NAME);
        try {
            final var adminIndexPkUserEntry = cache.getPkIndexAdminUserEntry(username);
            final var userEntry = cache.getAdminUserEntry(username);
            if (adminIndexPkUserEntry != null && userEntry != null) {
                eraseAdminEntry(Globals.ADMIN_USERS_COLLECTION_NAME, userEntry, adminIndexPkUserEntry);
                cache.removeAdminUserEntry(username);
                clientTracker.deauthenticateUser(username);
            }
        } finally {
            releaseAdmin(Globals.ADMIN_USERS_COLLECTION_NAME);
        }
    }
}
