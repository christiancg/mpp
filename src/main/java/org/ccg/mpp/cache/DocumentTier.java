package org.ccg.mpp.cache;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.ccg.mpp.data.DbEntry;
import org.ccg.mpp.data.PkIndexEntry;

final class DocumentTier {
    private final UserCache cache;

    DocumentTier(UserCache cache) {
        this.cache = cache;
    }

    private Map<String, ResidentDocuments> residents() {
        return cache.residentDocuments();
    }

    private ResidentDocuments residentFor(String collectionIdentifier) {
        return residents().computeIfAbsent(collectionIdentifier,
                _ -> new ResidentDocuments(cache.sizes().footprint().newCell(AccessKind.COLLECTION)));
    }

    void addEntryToCache(String projectName, String collName, DbEntry entry) {
        if (UserCache.isReservedProject(projectName)) {
            return;
        }
        final var collId = Cache.getCollectionIdentifier(projectName, collName);
        final var existing = residents().get(collId);
        if (existing != null && existing.containsKey(entry.get_id())) {
            existing.put(entry);
            return;
        }
        if (!cache.admits(projectName, AccessKind.COLLECTION, collName, null,
                () -> CacheSizeEstimator.estimateCollectionEntry(entry))) {
            return;
        }
        residentFor(collId).put(entry);
    }

    void addEntriesToCache(String projectName, String collName, List<DbEntry> entries) {
        if (UserCache.isReservedProject(projectName)) {
            return;
        }
        final var collId = Cache.getCollectionIdentifier(projectName, collName);
        final var existing = residents().get(collId);
        final var newEntries = new ArrayList<DbEntry>();
        for (var e : entries) {
            if (existing != null && existing.containsKey(e.get_id())) {
                existing.put(e);
            } else {
                newEntries.add(e);
            }
        }
        if (newEntries.isEmpty() || !cache.admits(projectName, AccessKind.COLLECTION, collName, null,
                () -> CacheSizeEstimator.estimateCollectionEntries(newEntries))) {
            return;
        }
        final var target = residentFor(collId);
        for (var e : newEntries) {
            target.put(e);
        }
    }

    DbEntry getById(String projectName, String collName, PkIndexEntry idxEntry) throws Exception {
        if (cache.isCachingDisabled(projectName)) {
            return cache.fs().getById(idxEntry);
        }
        final var collectionIdentifier = Cache.getCollectionIdentifier(projectName, collName);
        final var resident = residents().get(collectionIdentifier);
        final var hit = resident == null ? null : resident.get(idxEntry.getValue());
        if (hit != null) {
            return hit;
        }
        final var entry = cache.fs().getById(idxEntry);
        if (cache.locks().holdsCollectionLock(projectName, collName) && cache.admits(projectName, AccessKind.COLLECTION,
                collName, null, () -> CacheSizeEstimator.estimateCollectionEntry(entry))) {
            residentFor(collectionIdentifier).put(entry);
        }
        return entry;
    }

    Map<String, DbEntry> getCachedCollection(String projectName, String collName) {
        final var resident = residents().get(Cache.getCollectionIdentifier(projectName, collName));
        return resident == null ? null : resident.documents();
    }

    Map<String, DbEntry> admitWholeCollection(String projectName, String collName, Map<String, DbEntry> loaded) {
        if (cache.isCachingDisabled(projectName)) {
            return loaded;
        }
        final var asMap = loaded instanceof ConcurrentHashMap<String, DbEntry> concurrent
                ? concurrent
                : new ConcurrentHashMap<>(loaded);
        return admitCollected(projectName, collName, asMap, CacheSizeEstimator.estimateCollectionSize(asMap), loaded);
    }

    Map<String, DbEntry> admitCollected(String projectName, String collName,
            ConcurrentHashMap<String, DbEntry> collected, long measuredBytes, Map<String, DbEntry> otherwise) {
        if (cache.isCachingDisabled(projectName)
                || !cache.admits(projectName, AccessKind.COLLECTION, collName, null, () -> measuredBytes)) {
            return otherwise;
        }
        final var attached = ResidentDocuments.attach(collected,
                cache.sizes().footprint().newCell(AccessKind.COLLECTION), measuredBytes);
        final var replaced = residents().put(Cache.getCollectionIdentifier(projectName, collName), attached);
        if (replaced != null) {
            replaced.retire();
        }
        return collected;
    }

    List<DbEntry> getEntriesByIds(String projectName, String collName, Set<String> ids, PkRows pinned)
            throws IOException {
        if (ids == null || ids.isEmpty()) {
            return new ArrayList<>();
        }
        final var result = new ArrayList<DbEntry>(ids.size());
        final var collectionIdentifier = Cache.getCollectionIdentifier(projectName, collName);
        final var cachingDisabled = cache.isCachingDisabled(projectName);
        final var resident = cachingDisabled ? null : residents().get(collectionIdentifier);
        final var missingIds = new ArrayList<String>();
        for (var id : ids) {
            final var hit = resident != null ? resident.get(id) : null;
            if (hit != null) {
                result.add(hit);
            } else {
                missingIds.add(id);
            }
        }
        if (missingIds.isEmpty()) {
            return result;
        }
        final var toRead = cache.pkTier().rowsFor(projectName, collName, missingIds, pinned);
        if (toRead.isEmpty()) {
            return result;
        }
        final var read = cache.fs().getByIndexEntries(toRead);
        result.addAll(read);
        if (!cachingDisabled && cache.locks().holdsCollectionLock(projectName, collName) && cache.admits(projectName,
                AccessKind.COLLECTION, collName, null, () -> CacheSizeEstimator.estimateCollectionEntries(read))) {
            final var target = residentFor(collectionIdentifier);
            for (var e : read) {
                target.put(e);
            }
        }
        return result;
    }

    void evictEntry(String projectName, String collName, String pk) {
        final var resident = residents().get(Cache.getCollectionIdentifier(projectName, collName));
        if (resident != null) {
            resident.remove(pk);
        }
    }

    void evictCollectionDocuments(String collectionIdentifier) {
        final var removed = residents().remove(collectionIdentifier);
        if (removed != null) {
            removed.retire();
        }
    }
}
