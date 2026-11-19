package org.ccg.mpp.cache;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.ccg.mpp.data.PkIndexEntry;
import org.ccg.mpp.fs.VacuumedPage;

final class PkIndexTier {
    static final long LOCK_FREE_POINT_WAIT_MILLIS = 100L;

    private final UserCache cache;

    private interface ResidentRead<R> {
        R read(List<PkIndexEntry> resident) throws IOException;
    }

    private interface DiskRead<R> {
        R read() throws IOException;
    }

    PkIndexTier(UserCache cache) {
        this.cache = cache;
    }

    List<PkIndexEntry> resident(String collectionIdentifier) {
        return cache.pkIndexes().get(collectionIdentifier);
    }

    List<PkIndexEntry> loadIfNecessary(String projectName, String collName) throws IOException {
        final var collectionIdentifier = Cache.getCollectionIdentifier(projectName, collName);
        final var primaryKeyIndex = resident(collectionIdentifier);
        final var locked = cache.locks().holdsCollectionLock(projectName, collName);
        if (primaryKeyIndex == null) {
            final var fromDisk = cache.wholeLoads().pkIndex(projectName, collName, locked,
                    bytes -> cache.loadsAdmitted(projectName, collName, AccessKind.PK_INDEX, bytes));
            if (fromDisk != null && !cache.isCachingDisabled(projectName) && locked) {
                return admit(projectName, collName, collectionIdentifier, fromDisk);
            }
            return fromDisk;
        }
        if (locked) {
            return primaryKeyIndex;
        }
        return snapshotForLockFreeReader(projectName, collName, collectionIdentifier);
    }

    WriterPkRows forWriter(String projectName, String collName, Collection<String> ids) throws IOException {
        final var collectionIdentifier = Cache.getCollectionIdentifier(projectName, collName);
        if (resident(collectionIdentifier) == null && cache.locks().holdsCollectionLock(projectName, collName)
                && pointScansOnly(projectName, collName, collectionIdentifier)) {
            final var found = ids.isEmpty()
                    ? Map.<String, PkIndexEntry>of()
                    : cache.fs().pkRowsFor(projectName, collName, ids);
            return WriterPkRows.scanned(found);
        }
        return WriterPkRows.of(loadIfNecessary(projectName, collName));
    }

    private List<PkIndexEntry> admit(String projectName, String collName, String collectionIdentifier,
            List<PkIndexEntry> fromDisk) {
        final var admitted = resident(collectionIdentifier);
        if (admitted != null) {
            return admitted;
        }
        final var displaced = cache.sizes().refusedPk(collectionIdentifier);
        if (displaced != null && displaced.displaced() && crowdsFieldIndexes(displaced.bytes())) {
            return fromDisk;
        }
        final var rows = fromDisk.size();
        final var minimumEntryBytes = CacheSizeEstimator.minimumPkEntryBytes();
        if (!cache.admissionFeasible(AccessKind.PK_INDEX, rows * minimumEntryBytes)) {
            refuse(projectName, collName, collectionIdentifier, minimumEntryBytes, rows);
            return fromDisk;
        }
        final var bytesPerEntry = CacheSizeEstimator.pkIndexBytesPerEntry(fromDisk);
        if (!cache.shouldCache(projectName,
                new AdmissionRequest(AccessKind.PK_INDEX, projectName, collName, null, bytesPerEntry * rows))) {
            refuse(projectName, collName, collectionIdentifier, bytesPerEntry, rows);
            return fromDisk;
        }
        final var cached = PkIndexList.of(fromDisk);
        cached.chargeTo(cache.sizes().footprint().newCell(AccessKind.PK_INDEX), bytesPerEntry);
        final var raced = cache.pkIndexes().putIfAbsent(collectionIdentifier, cached);
        if (raced != null) {
            cached.retireCharge();
            return raced;
        }
        cache.sizes().clearRefusedPk(collectionIdentifier);
        return cached;
    }

    private void refuse(String projectName, String collName, String collectionIdentifier, long bytesPerEntry,
            long rows) {
        cache.sizes().refusePk(collectionIdentifier, bytesPerEntry, rows);
        cache.fitWarnings().pkIndexRefused(projectName, collName, bytesPerEntry * rows, cache.maxMemoryBytes(),
                cache.footprintBytes());
    }

    private boolean crowdsFieldIndexes(long bytes) {
        return !cache.admissionFeasible(AccessKind.FIELD_INDEX, bytes);
    }

    private List<PkIndexEntry> snapshotForLockFreeReader(String projectName, String collName,
            String collectionIdentifier) throws IOException {
        var held = false;
        try {
            held = cache.locks().tryLockRead(projectName, collName, 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!held) {
            return cache.wholeLoads().transientPkIndex(projectName, collName);
        }
        try {
            final var shared = resident(collectionIdentifier);
            return shared != null
                    ? new ArrayList<>(shared)
                    : cache.wholeLoads().transientPkIndex(projectName, collName);
        } finally {
            cache.locks().releaseRead(projectName, collName);
        }
    }

    void repositionAfterVacuum(VacuumedPage vacuumed) {
        final var primaryKeyIndex = resident(
                Cache.getCollectionIdentifier(vacuumed.projectName(), vacuumed.collName()));
        if (primaryKeyIndex != null) {
            vacuumed.reposition(id -> PkIndexList.rowOf(primaryKeyIndex, id));
        }
    }

    PkIndexEntry rowFor(String projectName, String collName, String id) throws IOException {
        final var collectionIdentifier = Cache.getCollectionIdentifier(projectName, collName);
        if (cache.locks().holdsCollectionLock(projectName, collName)) {
            final var held = resident(collectionIdentifier);
            if (held != null) {
                return detached(PkIndexList.rowOf(held, id));
            }
            if (pointScansOnly(projectName, collName, collectionIdentifier)) {
                return cache.fs().pkRowsFor(projectName, collName, List.of(id)).get(id);
            }
            return detached(PkIndexList.rowOf(loadIfNecessary(projectName, collName), id));
        }
        return lockFreeRead(projectName, collName, collectionIdentifier,
                resident -> detached(PkIndexList.rowOf(resident, id)),
                () -> cache.fs().pkRowsFor(projectName, collName, List.of(id)).get(id));
    }

    List<PkIndexEntry> rowsFor(String projectName, String collName, Collection<String> ids, PkRows pinned)
            throws IOException {
        if (pinned != null) {
            final var rows = pinned.rows(projectName, collName);
            if (rows != null) {
                return pick(rows, ids);
            }
        }
        final var collectionIdentifier = Cache.getCollectionIdentifier(projectName, collName);
        if (cache.locks().holdsCollectionLock(projectName, collName)) {
            final var held = resident(collectionIdentifier);
            if (held != null) {
                return pick(held, ids);
            }
            if (pointScansOnly(projectName, collName, collectionIdentifier)) {
                return scanned(projectName, collName, ids);
            }
            return pick(loadIfNecessary(projectName, collName), ids);
        }
        return lockFreeRead(projectName, collName, collectionIdentifier, resident -> pick(resident, ids),
                () -> scanned(projectName, collName, ids));
    }

    int countForCoverage(String projectName, String collName) throws IOException {
        final var collectionIdentifier = Cache.getCollectionIdentifier(projectName, collName);
        if (cache.locks().holdsCollectionLock(projectName, collName)) {
            return heldCount(projectName, collName, collectionIdentifier);
        }
        final var shared = resident(collectionIdentifier);
        if (shared != null) {
            return publishedSize(shared);
        }
        final var trusted = cache.fs().trustedLiveRows(projectName, collName);
        return trusted.isPresent() ? (int) trusted.getAsLong() : UserCache.UNKNOWN_COUNT;
    }

    int size(String projectName, String collName) throws IOException {
        final var collectionIdentifier = Cache.getCollectionIdentifier(projectName, collName);
        if (cache.locks().holdsCollectionLock(projectName, collName)) {
            return heldCount(projectName, collName, collectionIdentifier);
        }
        final var shared = resident(collectionIdentifier);
        if (shared != null) {
            return publishedSize(shared);
        }
        final var trusted = cache.fs().trustedLiveRows(projectName, collName);
        return trusted.isPresent()
                ? (int) trusted.getAsLong()
                : cache.wholeLoads().transientPkIndex(projectName, collName).size();
    }

    private int heldCount(String projectName, String collName, String collectionIdentifier) throws IOException {
        final var held = resident(collectionIdentifier);
        if (held != null) {
            return held.size();
        }
        if (pointScansOnly(projectName, collName, collectionIdentifier)) {
            final var trusted = cache.fs().trustedLiveRows(projectName, collName);
            if (trusted.isPresent()) {
                return (int) trusted.getAsLong();
            }
        }
        return loadIfNecessary(projectName, collName).size();
    }

    private static int publishedSize(List<PkIndexEntry> resident) {
        return resident instanceof PkIndexList list ? list.publishedSize() : resident.size();
    }

    boolean pointScansOnly(String projectName, String collName) {
        return resident(Cache.getCollectionIdentifier(projectName, collName)) == null
                && pointScansOnly(projectName, collName, Cache.getCollectionIdentifier(projectName, collName));
    }

    private boolean pointScansOnly(String projectName, String collName, String collectionIdentifier) {
        if (UserCache.isReservedProject(projectName)) {
            return false;
        }
        if (cache.isCachingDisabled(projectName)) {
            return true;
        }
        final var refused = cache.sizes().refusedPk(collectionIdentifier);
        if (refused != null) {
            return !cache.admissionFeasible(AccessKind.PK_INDEX, refused.bytes())
                    || refused.displaced() && crowdsFieldIndexes(refused.bytes());
        }
        final var rows = cache.fs().trustedLiveRows(projectName, collName);
        final var minimumEntryBytes = CacheSizeEstimator.minimumPkEntryBytes();
        if (rows.isPresent() && !cache.admissionFeasible(AccessKind.PK_INDEX, rows.getAsLong() * minimumEntryBytes)) {
            refuse(projectName, collName, collectionIdentifier, minimumEntryBytes, rows.getAsLong());
            return true;
        }
        return false;
    }

    private <R> R lockFreeRead(String projectName, String collName, String collectionIdentifier,
            ResidentRead<R> onResident, DiskRead<R> fromDisk) throws IOException {
        if (resident(collectionIdentifier) == null || !tryPointLock(projectName, collName)) {
            return fromDisk.read();
        }
        try {
            final var shared = resident(collectionIdentifier);
            return shared != null ? onResident.read(shared) : fromDisk.read();
        } finally {
            cache.locks().releaseRead(projectName, collName);
        }
    }

    private boolean tryPointLock(String projectName, String collName) {
        try {
            return cache.locks().tryLockRead(projectName, collName, LOCK_FREE_POINT_WAIT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private List<PkIndexEntry> scanned(String projectName, String collName, Collection<String> ids) throws IOException {
        return new ArrayList<>(cache.fs().pkRowsFor(projectName, collName, ids).values());
    }

    private static List<PkIndexEntry> pick(List<PkIndexEntry> rows, Collection<String> ids) {
        final var picked = new ArrayList<PkIndexEntry>(ids.size());
        for (final var id : ids) {
            final var row = PkIndexList.rowOf(rows, id);
            if (row != null) {
                picked.add(row.detachedCopy());
            }
        }
        return picked;
    }

    private static PkIndexEntry detached(PkIndexEntry row) {
        return row == null ? null : row.detachedCopy();
    }

    void evict(String collectionIdentifier) {
        final var removed = cache.pkIndexes().remove(collectionIdentifier);
        if (removed instanceof PkIndexList list) {
            list.retireCharge();
        }
    }
}
