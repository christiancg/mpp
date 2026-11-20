package org.ccg.mpp.cache;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.ccg.mpp.data.DbEntry;
import org.ccg.mpp.data.FieldIndexEntry;
import org.ccg.mpp.data.PkIndexEntry;

interface UserCacheDelegate {
    UserCache userCache();

    default List<PkIndexEntry> getPkIndexAndLoadIfNecessary(String projectName, String collName) throws IOException {
        return userCache().getPkIndexAndLoadIfNecessary(projectName, collName);
    }

    default PkIndexEntry pkRowFor(String projectName, String collName, String id) throws IOException {
        return userCache().pkRowFor(projectName, collName, id);
    }

    default int pkCountForCoverage(String projectName, String collName) {
        try {
            return userCache().pkCountForCoverage(projectName, collName);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    default void recordFieldIndexAccess(String projectName, String collName, String fieldName) {
        userCache().recordFieldIndexAccess(projectName, collName, fieldName);
    }

    default void addEntryToCache(String projectName, String collName, DbEntry entry) {
        userCache().addEntryToCache(projectName, collName, entry);
    }

    default void addEntriesToCache(String projectName, String collName, List<DbEntry> entries) {
        userCache().addEntriesToCache(projectName, collName, entries);
    }

    default DbEntry getById(String projectName, String collName, PkIndexEntry idxEntry) throws Exception {
        return userCache().getById(projectName, collName, idxEntry);
    }

    default void evictEntry(String projectName, String collName, String pk) {
        userCache().evictEntry(projectName, collName, pk);
    }

    default void evictFieldIndexAllTypes(String projectName, String collName, String fieldName) {
        userCache().evictFieldIndexAllTypes(projectName, collName, fieldName);
    }

    default List<FieldIndexEntry<?>> residentFieldIndex(String projectName, String collName, String indexIdentifier) {
        return userCache().residentFieldIndex(projectName, collName, indexIdentifier);
    }

    default void admitMaintainedFieldIndex(String projectName, String collName, String indexIdentifier,
            List<FieldIndexEntry<?>> written) {
        userCache().admitMaintainedFieldIndex(projectName, collName, indexIdentifier, written);
    }

    default void rememberFieldIndexBatch(String projectName, String collName, String indexIdentifier, int previousCount,
            long deltaBytes, int changedIds) {
        userCache().rememberFieldIndexBatch(projectName, collName, indexIdentifier, previousCount, deltaBytes,
                changedIds);
    }

    default String canonicalPkId(String projectName, String collName, String id) {
        return userCache().canonicalPkId(projectName, collName, id);
    }

    default void signalIfOverBudget() {
        userCache().signalIfOverBudget();
    }

    default FieldIndexLookup fieldIndexLookup(String projectName, String collName, String fieldName) {
        return userCache().fieldIndexLookup(projectName, collName, fieldName);
    }

    default void admitFieldIndexLookup(String projectName, String collName, String fieldName, FieldIndexLookup lookup) {
        userCache().admitFieldIndexLookup(projectName, collName, fieldName, lookup);
    }

    default void evictFieldIndexLookup(String projectName, String collName, String fieldName) {
        userCache().evictFieldIndexLookup(projectName, collName, fieldName);
    }

    default void evictFieldIndex(String projectName, String collName, String indexIdentifier) {
        userCache().evictFieldIndex(projectName, collName, indexIdentifier);
    }

    default List<CacheableResource> listCacheableResources() {
        return userCache().listCacheableResources();
    }
}
