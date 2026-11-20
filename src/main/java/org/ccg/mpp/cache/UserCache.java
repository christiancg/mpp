package org.ccg.mpp.cache;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.ConfigKey;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.DbEntry;
import org.ccg.mpp.data.FieldIndexEntry;
import org.ccg.mpp.data.IndexKind;
import org.ccg.mpp.data.PkIndexEntry;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.fs.VacuumedPage;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.req.agg.operators.FieldOperator;

public class UserCache {
    public static final int UNKNOWN_COUNT = -1;

    private final Configuration configuration = Configuration.getInstance();
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final ResourceLocking rl = IocContainer.get(ResourceLocking.class);
    private final UsageTracker usageTracker = IocContainer.get(UsageTracker.class);
    private final Map<String, List<PkIndexEntry>> pkIndexMap = new ConcurrentHashMap<>();
    private final Map<String, Map<String, List<FieldIndexEntry<?>>>> fieldIndexMap = new ConcurrentHashMap<>();
    private final FieldIndexLookups fieldLookups = new FieldIndexLookups();
    private final Map<String, ResidentDocuments> collectionMap = new ConcurrentHashMap<>();
    private final UserCacheSizes sizes = new UserCacheSizes();
    private final WholeLoads wholeLoads = new WholeLoads(() -> fs,
            configuration.intValue(ConfigKey.TRANSIENT_LOAD_PERMITS));
    private final OverBudgetSignal overBudget = new OverBudgetSignal(configuration);
    private final CacheFitWarnings fitWarnings = new CacheFitWarnings();
    private final PkIndexTier pkTier = new PkIndexTier(this);
    private final DocumentTier documentTier = new DocumentTier(this);
    private final AdmissionEviction admission = new AdmissionEviction(this);

    Map<String, List<PkIndexEntry>> pkIndexes() {
        return pkIndexMap;
    }

    Map<String, ResidentDocuments> residentDocuments() {
        return collectionMap;
    }

    Map<String, Map<String, List<FieldIndexEntry<?>>>> fieldIndexes() {
        return fieldIndexMap;
    }

    UserCacheSizes sizes() {
        return sizes;
    }

    FileSystem fs() {
        return fs;
    }

    ResourceLocking locks() {
        return rl;
    }

    UsageTracker usageTracker() {
        return usageTracker;
    }

    WholeLoads wholeLoads() {
        return wholeLoads;
    }

    CacheFitWarnings fitWarnings() {
        return fitWarnings;
    }

    PkIndexTier pkTier() {
        return pkTier;
    }

    AdmissionEviction admission() {
        return admission;
    }

    long maxMemoryBytes() {
        return configuration.getMaxMemoryBytes();
    }

    public List<PkIndexEntry> getPkIndexAndLoadIfNecessary(String projectName, String collName) throws IOException {
        return pkTier.loadIfNecessary(projectName, collName);
    }

    public PkIndexEntry pkRowFor(String projectName, String collName, String id) throws IOException {
        return pkTier.rowFor(projectName, collName, id);
    }

    public int pkCountForCoverage(String projectName, String collName) throws IOException {
        return pkTier.countForCoverage(projectName, collName);
    }

    public int pkIndexSize(String projectName, String collName) throws IOException {
        return pkTier.size(projectName, collName);
    }

    public boolean isResidentPkIndex(String projectName, String collName, List<PkIndexEntry> rows) {
        return rows != null && pkIndexMap.get(Cache.getCollectionIdentifier(projectName, collName)) == rows;
    }

    public boolean tryPinTransientPkIndex() {
        return wholeLoads.tryPin();
    }

    public void unpinTransientPkIndex() {
        wholeLoads.unpin();
    }

    public void repositionAfterVacuum(VacuumedPage vacuumed) {
        pkTier.repositionAfterVacuum(vacuumed);
    }

    public boolean isCachingDisabled(String projectName) {
        return isReservedProject(projectName) || configuration.isCachingDisabled();
    }

    static boolean isReservedProject(String projectName) {
        return Globals.ADMIN_PROJECT_NAME.equals(projectName) || Globals.ADMIN_PAGES_PROJECT_NAME.equals(projectName);
    }

    public boolean shouldCache(String projectName, AdmissionRequest request) {
        if (isCachingDisabled(projectName)) {
            return false;
        }
        if (configuration.isCacheUnlimited()) {
            return true;
        }
        final var maxBytes = configuration.getMaxMemoryBytes();
        return footprintBytes() + request.bytes() <= maxBytes || admission.makeRoom(request, maxBytes);
    }

    public boolean wouldAdmit(AdmissionRequest request) {
        if (isCachingDisabled(request.projectName())) {
            return false;
        }
        if (configuration.isCacheUnlimited()) {
            return true;
        }
        final var maxBytes = configuration.getMaxMemoryBytes();
        return footprintBytes() + request.bytes() <= maxBytes || admission.mayMakeRoom(request, maxBytes);
    }

    boolean admits(String projectName, AccessKind tier, String collName, String fieldName, LongSupplier bytes) {
        return !isCachingDisabled(projectName) && shouldCache(projectName,
                new AdmissionRequest(tier, projectName, collName, fieldName, bytes.getAsLong()));
    }

    boolean admissionFeasible(AccessKind tier, long bytes) {
        return configuration.isCacheUnlimited() || admission.feasible(tier, bytes, configuration.getMaxMemoryBytes());
    }

    boolean loadsAdmitted(String projectName, String collName, AccessKind tier, long estimatedBytes) {
        return isReservedProject(projectName) || rl.holdsCollectionLock(projectName, collName)
                && !isCachingDisabled(projectName) && admissionFeasible(tier, estimatedBytes);
    }

    public long footprintBytes() {
        return sizes.footprint().bytes();
    }

    public <T> List<FieldIndexEntry<T>> getFieldIndexAndLoadIfNecessary(String projectName, String collName,
            String fieldName, Class<T> indexType) throws IOException {
        return loadIndex(projectName, collName, Cache.getIndexIdentifier(fieldName, indexType),
                () -> wholeLoads.fieldIndex(projectName, collName, fieldName, indexType,
                        bytes -> loadsAdmitted(projectName, collName, AccessKind.FIELD_INDEX, bytes)));
    }

    public List<FieldIndexEntry<String>> getHashIndexAndLoadIfNecessary(String projectName, String collName,
            String fieldName, IndexKind kind) throws IOException {
        return loadIndex(projectName, collName, Cache.getHashIndexIdentifier(fieldName, kind.label()),
                () -> wholeLoads.hashIndex(projectName, collName, fieldName, kind,
                        bytes -> loadsAdmitted(projectName, collName, AccessKind.FIELD_INDEX, bytes)));
    }

    public List<FieldIndexEntry<?>> residentFieldIndex(String projectName, String collName, String indexIdentifier) {
        final var index = fieldIndexMap.get(Cache.getCollectionIdentifier(projectName, collName));
        return index == null ? null : index.get(indexIdentifier);
    }

    public void admitMaintainedFieldIndex(String projectName, String collName, String indexIdentifier,
            List<FieldIndexEntry<?>> written) {
        if (rl.holdsCollectionLock(projectName, collName)
                && residentFieldIndex(projectName, collName, indexIdentifier) == null
                && fieldIndexMayFit(projectName, collName, indexIdentifier, written.size())) {
            admitFieldIndex(projectName, collName, indexIdentifier, FieldIndexList.of(written));
        }
    }

    public void rememberFieldIndexBatch(String projectName, String collName, String indexIdentifier, int previousCount,
            long deltaBytes, int changedIds) {
        final var resident = residentFieldIndex(projectName, collName, indexIdentifier);
        if (resident != null) {
            sizes.adjustFieldIndex(Cache.getCollectionIdentifier(projectName, collName), indexIdentifier, previousCount,
                    resident.size(), deltaBytes, changedIds);
            signalIfOverBudget();
        }
    }

    public void onOverBudget(Runnable listener) {
        overBudget.listen(listener);
    }

    public void signalIfOverBudget() {
        overBudget.signalIfOver(this::footprintBytes);
    }

    public FieldIndexLookup fieldIndexLookup(String projectName, String collName, String fieldName) {
        return fieldLookups.get(Cache.getCollectionIdentifier(projectName, collName), fieldName);
    }

    public void admitFieldIndexLookup(String projectName, String collName, String fieldName, FieldIndexLookup lookup) {
        if (!rl.holdsCollectionLock(projectName, collName) || isCachingDisabled(projectName)) {
            evictFieldIndexLookup(projectName, collName, fieldName);
            return;
        }
        final var resident = fieldIndexLookup(projectName, collName, fieldName);
        if (resident == lookup || admits(projectName, AccessKind.FIELD_INDEX, collName, fieldName,
                () -> CacheSizeEstimator.estimateFieldIndexLookupSize(lookup.size()))) {
            fieldLookups.put(Cache.getCollectionIdentifier(projectName, collName), fieldName, lookup,
                    sizes.footprint());
            signalIfOverBudget();
        } else {
            evictFieldIndexLookup(projectName, collName, fieldName);
        }
    }

    public void evictFieldIndexLookup(String projectName, String collName, String fieldName) {
        fieldLookups.evict(Cache.getCollectionIdentifier(projectName, collName), fieldName);
    }

    private <T> List<FieldIndexEntry<T>> loadIndex(String projectName, String collName, String indexIdentifier,
            IndexLoader<T> loader) throws IOException {
        final var existingIndex = residentFieldIndex(projectName, collName, indexIdentifier);
        if (existingIndex != null) {
            @SuppressWarnings("unchecked")
            final var typed = (List<FieldIndexEntry<T>>) (List<?>) existingIndex;
            return typed;
        }
        final var indexEntries = loader.load();
        if (indexEntries != null && rl.holdsCollectionLock(projectName, collName)
                && fieldIndexMayFit(projectName, collName, indexIdentifier, indexEntries.size())) {
            admitFieldIndex(projectName, collName, indexIdentifier, FieldIndexList.of(indexEntries));
        }
        return indexEntries;
    }

    private boolean fieldIndexMayFit(String projectName, String collName, String indexIdentifier, int entries) {
        final var lowerBound = CacheSizeEstimator.minimumFieldIndexBytes(entries);
        final var request = AdmissionRequest.forFieldIndex(projectName, collName, indexIdentifier, lowerBound);
        if (admissionFeasible(AccessKind.FIELD_INDEX, lowerBound)
                || !isCachingDisabled(projectName) && admission.makeRoomInTier(request, maxMemoryBytes())) {
            return true;
        }
        if (!isCachingDisabled(projectName)) {
            refuseFieldIndex(request, indexIdentifier);
        }
        return false;
    }

    private void refuseFieldIndex(AdmissionRequest request, String indexIdentifier) {
        fitWarnings.fieldIndexRefused(request.projectName(), request.collName(), indexIdentifier, request.bytes(),
                maxMemoryBytes(), footprintBytes());
        sizes.refuseFieldIndex(Cache.getCollectionIdentifier(request.projectName(), request.collName()),
                indexIdentifier, request.bytes());
        admission.recordRefusal(request);
    }

    private void admitFieldIndex(String projectName, String collName, String indexIdentifier,
            List<FieldIndexEntry<?>> copy) {
        if (isCachingDisabled(projectName)) {
            return;
        }
        final var collectionIdentifier = Cache.getCollectionIdentifier(projectName, collName);
        SharedIdLists.share(pkIndexMap.get(collectionIdentifier), copy);
        final var request = AdmissionRequest.forFieldIndex(projectName, collName, indexIdentifier,
                CacheSizeEstimator.estimateFieldIndexSize(copy));
        if (!shouldCache(projectName, request)
                && !(admission.makeRoomInTier(request, maxMemoryBytes()) && shouldCache(projectName, request))) {
            refuseFieldIndex(request, indexIdentifier);
            return;
        }
        final var measured = request.bytes();
        sizes.clearRefusedFieldIndex(collectionIdentifier, indexIdentifier);
        fieldIndexMap.compute(collectionIdentifier, (_, existing) -> {
            final var indexes = existing != null ? existing : new ConcurrentHashMap<String, List<FieldIndexEntry<?>>>();
            if (indexes.putIfAbsent(indexIdentifier, copy) == null) {
                sizes.rememberMeasuredFieldIndex(collectionIdentifier, indexIdentifier, measured, copy);
            }
            return indexes;
        });
    }

    private interface IndexLoader<T> {
        List<FieldIndexEntry<T>> load() throws IOException;
    }

    public <T> Set<String> getIdsFromIndex(String projectName, String collName, String fieldName,
            FieldOperator operator, T value) throws IOException {
        try {
            rl.lockIndexRead(projectName, collName, fieldName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while acquiring index read lock", e);
        }
        try {
            final var result = IndexLookupResolver.doGetIdsFromIndex(this, projectName, collName, fieldName, operator,
                    value);
            if (result == null) {
                return null;
            }
            recordFieldIndexAccess(projectName, collName, fieldName);
            return result;
        } finally {
            rl.releaseIndexRead(projectName, collName, fieldName);
        }
    }

    public void recordFieldIndexAccess(String projectName, String collName, String fieldName) {
        usageTracker.recordAccess(AccessKind.FIELD_INDEX, projectName, collName, fieldName);
    }

    public void addEntryToCache(String projectName, String collName, DbEntry entry) {
        documentTier.addEntryToCache(projectName, collName, entry);
    }

    public void addEntriesToCache(String projectName, String collName, List<DbEntry> entries) {
        documentTier.addEntriesToCache(projectName, collName, entries);
    }

    public DbEntry getById(String projectName, String collName, PkIndexEntry idxEntry) throws Exception {
        return documentTier.getById(projectName, collName, idxEntry);
    }

    public Map<String, DbEntry> getCachedCollection(String projectName, String collName) {
        return documentTier.getCachedCollection(projectName, collName);
    }

    public Map<String, DbEntry> admitWholeCollection(String projectName, String collName, Map<String, DbEntry> loaded) {
        return documentTier.admitWholeCollection(projectName, collName, loaded);
    }

    void admitScannedCollection(String projectName, String collName, ConcurrentHashMap<String, DbEntry> collected,
            long measuredBytes) {
        documentTier.admitCollected(projectName, collName, collected, measuredBytes, collected);
    }

    public List<DbEntry> getEntriesByIds(String projectName, String collName, Set<String> ids) throws IOException {
        return documentTier.getEntriesByIds(projectName, collName, ids, null);
    }

    public List<DbEntry> getEntriesByIds(String projectName, String collName, Set<String> ids, PkRows pinned)
            throws IOException {
        return documentTier.getEntriesByIds(projectName, collName, ids, pinned);
    }

    public void evictEntry(String projectName, String collName, String pk) {
        documentTier.evictEntry(projectName, collName, pk);
    }

    public void evictProject(String projectName) {
        final var prefix = projectName + Globals.COLL_IDENTIFIER_SEPARATOR;
        for (final var collectionIdentifier : List.copyOf(pkIndexMap.keySet())) {
            if (collectionIdentifier.startsWith(prefix)) {
                pkTier.evict(collectionIdentifier);
            }
        }
        for (final var collectionIdentifier : List.copyOf(collectionMap.keySet())) {
            if (collectionIdentifier.startsWith(prefix)) {
                documentTier.evictCollectionDocuments(collectionIdentifier);
            }
        }
        fieldIndexMap.keySet().removeIf(key -> key.startsWith(prefix));
        fieldLookups.evictByPrefix(prefix);
        sizes.forgetByPrefix(prefix);
        fitWarnings.forgetByPrefix(prefix);
    }

    public void evictCollection(String projectName, String collName) {
        final var collIdentifier = Cache.getCollectionIdentifier(projectName, collName);
        pkTier.evict(collIdentifier);
        documentTier.evictCollectionDocuments(collIdentifier);
        fieldIndexMap.remove(collIdentifier);
        fieldLookups.evictCollection(collIdentifier);
        sizes.forgetCollection(collIdentifier);
        fitWarnings.forgetCollection(collIdentifier);
    }

    public void evictCollectionDocuments(String projectName, String collName) {
        documentTier.evictCollectionDocuments(Cache.getCollectionIdentifier(projectName, collName));
    }

    public void evictPkIndex(String projectName, String collName) {
        final var collIdentifier = Cache.getCollectionIdentifier(projectName, collName);
        pkTier.evict(collIdentifier);
        SharedIdLists.sharersOf(fieldIndexMap.get(collIdentifier))
                .forEach(key -> evictFieldIndex(projectName, collName, key));
    }

    public String canonicalPkId(String projectName, String collName, String id) {
        return SharedIdLists.canonicalId(pkIndexMap.get(Cache.getCollectionIdentifier(projectName, collName)), id);
    }

    public void evict(CacheableResource resource) {
        switch (resource.kind()) {
            case PK_INDEX -> evictPkIndex(resource.projectName(), resource.collName());
            case FIELD_INDEX -> evictFieldIndex(resource.projectName(), resource.collName(), resource.indexKey());
            default -> evictCollectionDocuments(resource.projectName(), resource.collName());
        }
    }

    public void evictFieldIndex(String projectName, String collName, String indexKey) {
        evictFieldIndexLookup(projectName, collName, CacheableResource.indexedFieldOf(indexKey));
        if (Cache.isFieldLookupIdentifier(indexKey)) {
            return;
        }
        final var collIdentifier = Cache.getCollectionIdentifier(projectName, collName);
        sizes.clearRefusedFieldIndex(collIdentifier, indexKey);
        fieldIndexMap.computeIfPresent(collIdentifier, (_, indexes) -> {
            indexes.remove(indexKey);
            sizes.forgetFieldIndex(collIdentifier, indexKey);
            return indexes.isEmpty() ? null : indexes;
        });
    }

    public void evictFieldIndexAllTypes(String projectName, String collName, String fieldName) {
        evictFieldIndexLookup(projectName, collName, fieldName);
        final var collIdentifier = Cache.getCollectionIdentifier(projectName, collName);
        final var prefix = fieldName + Globals.COLL_IDENTIFIER_SEPARATOR;
        sizes.clearRefusedField(collIdentifier, fieldName);
        fieldIndexMap.computeIfPresent(collIdentifier, (_, indexes) -> {
            indexes.keySet().removeIf(key -> key.equals(fieldName) || key.startsWith(prefix));
            sizes.forgetFieldIndexAllTypes(collIdentifier, fieldName);
            return indexes.isEmpty() ? null : indexes;
        });
    }

    public List<CacheableResource> listCacheableResources() {
        return listCacheableResources(Measure.FULL);
    }

    public List<CacheableResource> listCacheableResources(Measure measure) {
        final var result = new ArrayList<CacheableResource>();
        pkIndexMap.forEach((collId, rows) -> addResource(result, AccessKind.PK_INDEX, collId, null, pkBytes(rows)));
        collectionMap.forEach(
                (collId, documents) -> addResource(result, AccessKind.COLLECTION, collId, null, documents.bytes()));
        fieldIndexMap.forEach((collId, indexes) -> indexes.forEach((indexKey, entries) -> addResource(result,
                AccessKind.FIELD_INDEX, collId, indexKey, fieldIndexBytes(measure, collId, indexKey, entries))));
        fieldLookups.addResources(result);
        return result;
    }

    private static long pkBytes(List<PkIndexEntry> rows) {
        final var charged = rows instanceof PkIndexList list ? list.chargedBytes() : -1L;
        return charged >= 0 ? charged : rows.size() * CacheSizeEstimator.pkIndexBytesPerEntry(List.of());
    }

    private static void addResource(List<CacheableResource> result, AccessKind kind, String collId, String indexKey,
            long bytes) {
        final var parts = collId.split(Globals.COLL_IDENTIFIER_SEPARATOR_REGEX, 2);
        if (parts.length == 2) {
            result.add(new CacheableResource(kind, parts[0], parts[1], indexKey, bytes));
        }
    }

    private long fieldIndexBytes(Measure measure, String collId, String indexKey, List<FieldIndexEntry<?>> entries) {
        if (measure == Measure.FULL_FORCED || measure == Measure.FULL && sizes.needsMeasure(collId, indexKey)) {
            final var parts = collId.split(Globals.COLL_IDENTIFIER_SEPARATOR_REGEX, 2);
            final var fieldName = CacheableResource.indexedFieldOf(indexKey);
            if (parts.length == 2 && rl.tryLockIndexRead(parts[0], parts[1], fieldName)) {
                try {
                    return sizes.measureFieldIndex(collId, indexKey, entries);
                } finally {
                    rl.releaseIndexRead(parts[0], parts[1], fieldName);
                }
            }
        }
        return sizes.rememberedFieldIndexBytes(collId, indexKey, entries.size());
    }

    public boolean hasLoadedIndex(String projectName, String collName, String fieldName) {
        final var fieldIndexes = fieldIndexMap.get(Cache.getCollectionIdentifier(projectName, collName));
        if (fieldIndexes == null) {
            return false;
        }
        final var prefix = fieldName + Globals.COLL_IDENTIFIER_SEPARATOR;
        return fieldIndexes.keySet().stream().anyMatch(key -> key.equals(fieldName) || key.startsWith(prefix));
    }
}
