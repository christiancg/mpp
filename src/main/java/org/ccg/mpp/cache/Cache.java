package org.ccg.mpp.cache;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongFunction;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.DbEntry;
import org.ccg.mpp.data.FieldIndexEntry;
import org.ccg.mpp.data.IndexKind;
import org.ccg.mpp.data.admin.AdminPageEntry;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.fs.PageOccupancy;
import org.ccg.mpp.fs.VacuumedPage;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.req.agg.operators.FieldOperator;
import org.ccg.mpp.utils.RangeBounds;

public class Cache implements UserCacheDelegate, AdminCacheDelegate {
    private static final String FIELD_LOOKUP_LABEL = "~lookup";
    private static final ClassValue<String> INDEX_TYPE_NAMES = new ClassValue<>() {
        @Override
        protected String computeValue(Class<?> type) {
            final var name = type.getName();
            return name.substring(name.lastIndexOf('.') + 1);
        }
    };
    private static final Comparator<DbEntry> BY_ID = Comparator.comparing(DbEntry::get_id);
    private static final int MEMOIZED_INDEX_FIELDS = 4096;
    private static final Map<String, Map<Object, String>> INDEX_IDENTIFIERS = new ConcurrentHashMap<>();
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final AdminCache adminCache = IocContainer.get(AdminCache.class);
    private final UserCache userCache = IocContainer.get(UserCache.class);
    private final ScanOrderMemo scanOrder = new ScanOrderMemo();
    private final MemoryManagement memoryManagement = IocContainer.get(MemoryManagement.class);
    private final AbsentIndexTypes absentIndexTypes = new AbsentIndexTypes();
    private final ScanRefusals scanRefusals = new ScanRefusals();
    private final IndexIdCounts idCounts = IocContainer.get(IndexIdCounts.class);
    private final org.ccg.mpp.concurrency.ResourceLocking locks = IocContainer
            .get(org.ccg.mpp.concurrency.ResourceLocking.class);

    public Cache() {
        final var admins = adminCache;
        final var users = userCache;
        fs.onVacuum(vacuumed -> reposition(vacuumed, admins, users));
    }

    @Override
    public UserCache userCache() {
        return userCache;
    }

    @Override
    public AdminCache adminCache() {
        return adminCache;
    }

    public static String getCollectionIdentifier(String projectName, String collName) {
        return CollectionIdentifiers.of(projectName, collName);
    }

    public static CollectionNames canonicalNames(String projectName, String collName) {
        return CollectionIdentifiers.namesOf(projectName, collName);
    }

    public static String getIndexIdentifier(String fieldName, Class<?> fieldType) {
        return fieldName + Globals.COLL_IDENTIFIER_SEPARATOR + INDEX_TYPE_NAMES.get(fieldType);
    }

    public static String getIndexIdentifier(String fieldName, String typeLabel) {
        return fieldName + Globals.COLL_IDENTIFIER_SEPARATOR + typeLabel;
    }

    public static String memoizedIndexIdentifier(String fieldName, Class<?> fieldType) {
        final var identifiers = memoizedIdentifiersOf(fieldName);
        final var known = identifiers.get(fieldType);
        if (known != null) {
            return known;
        }
        final var identifier = getIndexIdentifier(fieldName, fieldType);
        identifiers.putIfAbsent(fieldType, identifier);
        return identifier;
    }

    public static String memoizedHashIndexIdentifier(String fieldName, IndexKind kind) {
        final var identifiers = memoizedIdentifiersOf(fieldName);
        final var known = identifiers.get(kind);
        if (known != null) {
            return known;
        }
        final var identifier = getHashIndexIdentifier(fieldName, kind.label());
        identifiers.putIfAbsent(kind, identifier);
        return identifier;
    }

    private static Map<Object, String> memoizedIdentifiersOf(String fieldName) {
        final var identifiers = INDEX_IDENTIFIERS.get(fieldName);
        if (identifiers != null) {
            return identifiers;
        }
        if (INDEX_IDENTIFIERS.size() >= MEMOIZED_INDEX_FIELDS) {
            INDEX_IDENTIFIERS.clear();
        }
        return INDEX_IDENTIFIERS.computeIfAbsent(fieldName, _ -> new ConcurrentHashMap<>());
    }

    public static String getFieldLookupIdentifier(String fieldName) {
        return fieldName + Globals.COLL_IDENTIFIER_SEPARATOR + FIELD_LOOKUP_LABEL;
    }

    public static boolean isFieldLookupIdentifier(String indexIdentifier) {
        return indexIdentifier.endsWith(Globals.COLL_IDENTIFIER_SEPARATOR + FIELD_LOOKUP_LABEL);
    }

    public static String getHashIndexIdentifier(String fieldName, String typeLabel) {
        return fieldName + Globals.COLL_IDENTIFIER_SEPARATOR + Globals.HASH_INDEX_KEY_PREFIX + typeLabel;
    }

    public LongFunction<PageOccupancy> pageOccupancy(String projectName, String collName) {
        return page -> {
            final var entry = adminCache.getAdminPageEntry(projectName, collName, page);
            return entry == null ? null : new PageOccupancy(entry.getPageSize(), entry.getEntryCount());
        };
    }

    private static void reposition(VacuumedPage vacuumed, AdminCache admins, UserCache users) {
        if (Globals.ADMIN_PROJECT_NAME.equals(vacuumed.projectName())
                || Globals.ADMIN_PAGES_PROJECT_NAME.equals(vacuumed.projectName())) {
            admins.repositionAfterVacuum(vacuumed);
        } else {
            users.repositionAfterVacuum(vacuumed);
        }
    }

    public <T> List<FieldIndexEntry<T>> getFieldIndexAndLoadIfNecessary(String projectName, String collName,
            String fieldName, Class<T> indexType) throws IOException {
        return userCache.getFieldIndexAndLoadIfNecessary(projectName, collName, fieldName, indexType);
    }

    public List<FieldIndexEntry<String>> getHashIndexAndLoadIfNecessary(String projectName, String collName,
            String fieldName, IndexKind kind) throws IOException {
        return userCache.getHashIndexAndLoadIfNecessary(projectName, collName, fieldName, kind);
    }

    public <T> Set<String> getIdsFromIndex(String projectName, String collName, String fieldName,
            FieldOperator operator, T value) throws IOException {
        return userCache.getIdsFromIndex(projectName, collName, fieldName, operator, value);
    }

    public Set<String> getIdsInRange(String projectName, String collName, RangeBounds bounds) throws IOException {
        return IndexLookupResolver.idsInRange(userCache, projectName, collName, bounds);
    }

    public OptionalLong countInRange(String projectName, String collName, RangeBounds bounds) throws IOException {
        return IndexLookupResolver.countInRange(userCache, projectName, collName, bounds);
    }

    public <T> OptionalLong countEquals(String projectName, String collName, String fieldName, T value)
            throws IOException {
        return IndexLookupResolver.countEquals(userCache, projectName, collName, fieldName, value);
    }

    public boolean answersFromIndexFile(String projectName, String collName, String fieldName, Class<?> type) {
        return IndexFileReads.fromFile(userCache, projectName, collName, fieldName, type);
    }

    public boolean reloadsLargeFieldIndex(String projectName, String collName, String fieldName) {
        return IndexFileReads.reloadsLargeIndex(userCache, projectName, collName, fieldName);
    }

    public OptionalLong countEqualsAny(String projectName, String collName, String fieldName, List<?> values)
            throws IOException {
        return IndexLookupResolver.countEqualsAny(userCache, projectName, collName, fieldName, values);
    }

    public List<DbEntry> getEntriesByIds(String projectName, String collName, Set<String> ids) throws IOException {
        return userCache.getEntriesByIds(projectName, collName, ids);
    }

    public List<DbEntry> getEntriesByIds(String projectName, String collName, Set<String> ids, PkRows pinned)
            throws IOException {
        return userCache.getEntriesByIds(projectName, collName, ids, pinned);
    }

    public Set<String> getIdsEqualToAny(String projectName, String collName, String fieldName,
            List<Object> lookupValues) throws IOException {
        return IndexLookupResolver.idsEqualToAny(userCache, projectName, collName, fieldName, lookupValues);
    }

    public void evictProject(String projectName) {
        CollectionIdentifiers.forgetProject(projectName);
        absentIndexTypes.forgetProject(projectName);
        idCounts.forgetProject(projectName);
        userCache.evictProject(projectName);
        adminCache.removeCollectionSchemasForProject(projectName);
        adminCache.removeProceduresForProject(projectName);
        adminCache.removeSchedulesForProject(projectName);
        adminCache.removeTriggersForProject(projectName);
    }

    public void evictCollection(String projectName, String collName) {
        absentIndexTypes.forgetCollection(getCollectionIdentifier(projectName, collName));
        idCounts.forgetCollection(projectName, collName);
        CollectionIdentifiers.forget(projectName, collName);
        userCache.evictCollection(projectName, collName);
        adminCache.removeCollectionSchema(projectName, collName);
        adminCache.removeTriggers(projectName, collName);
    }

    @Override
    public void evictFieldIndex(String projectName, String collName, String indexIdentifier) {
        absentIndexTypes.forget(getCollectionIdentifier(projectName, collName), indexIdentifier);
        idCounts.forget(projectName, collName, indexIdentifier);
        userCache.evictFieldIndex(projectName, collName, indexIdentifier);
    }

    @Override
    public void evictFieldIndexAllTypes(String projectName, String collName, String fieldName) {
        INDEX_IDENTIFIERS.remove(fieldName);
        absentIndexTypes.forgetField(getCollectionIdentifier(projectName, collName), fieldName);
        idCounts.forgetField(projectName, collName, fieldName);
        userCache.evictFieldIndexAllTypes(projectName, collName, fieldName);
    }

    public boolean isFieldIndexTypeAbsent(String projectName, String collName, String indexIdentifier) {
        return absentIndexTypes.isAbsent(getCollectionIdentifier(projectName, collName), indexIdentifier);
    }

    public void rememberFieldIndexTypeAbsent(String projectName, String collName, String indexIdentifier) {
        absentIndexTypes.remember(getCollectionIdentifier(projectName, collName), indexIdentifier);
    }

    public void forgetFieldIndexTypeAbsent(String projectName, String collName, String indexIdentifier) {
        absentIndexTypes.forget(getCollectionIdentifier(projectName, collName), indexIdentifier);
    }

    public WriterPkRows pkRowsForWriter(String projectName, String collName, Collection<String> ids)
            throws IOException {
        return userCache.pkTier().forWriter(projectName, collName, ids);
    }

    public boolean displacesColderIndexes(String projectName, String collName, String indexIdentifier) {
        final var bytes = userCache.sizes().refusedFieldIndexBytes(getCollectionIdentifier(projectName, collName),
                indexIdentifier);
        if (bytes == null || userCache.isCachingDisabled(projectName)) {
            return false;
        }
        final var request = AdmissionRequest.forFieldIndex(projectName, collName, indexIdentifier, bytes);
        if (userCache.admission().makeRoomInTier(request, userCache.maxMemoryBytes())) {
            return true;
        }
        userCache.admission().recordRefusal(request);
        return false;
    }

    public boolean pkServedByPointScans(String projectName, String collName) {
        return userCache.pkTier().pointScansOnly(projectName, collName);
    }

    public boolean isFieldIndexTypeRefused(String projectName, String collName, String indexIdentifier) {
        if (UserCache.isReservedProject(projectName)) {
            return false;
        }
        if (userCache.isCachingDisabled(projectName)) {
            return true;
        }
        final var bytes = userCache.sizes().refusedFieldIndexBytes(getCollectionIdentifier(projectName, collName),
                indexIdentifier);
        return bytes != null && !userCache.admissionFeasible(AccessKind.FIELD_INDEX, bytes);
    }

    public Set<String> fieldsKeepingPriorValues(String projectName, String collName) {
        if (UserCache.isReservedProject(projectName)) {
            return Set.of();
        }
        if (userCache.isCachingDisabled(projectName)) {
            return adminCache.getIndexesForCollection(projectName, collName);
        }
        return userCache.sizes().fieldsWithRefusedTypes(getCollectionIdentifier(projectName, collName));
    }

    public Map<String, DbEntry> getWholeCollection(String projectName, String collName) {
        final var wholeCollection = completeCachedCollection(projectName, collName);
        if (wholeCollection != null) {
            return wholeCollection;
        }
        try {
            final var loaded = readWholeCollection(projectName, collName);
            return locks.holdsCollectionLock(projectName, collName)
                    ? userCache.admitWholeCollection(projectName, collName, loaded)
                    : loaded;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public int pkIndexSize(String projectName, String collName) {
        try {
            return userCache.pkIndexSize(projectName, collName);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Map<String, DbEntry> completeCachedCollection(String projectName, String collName) {
        final var cached = userCache.getCachedCollection(projectName, collName);
        return cached != null && !cached.isEmpty() && cached.size() == pkCountForCoverage(projectName, collName)
                ? cached
                : null;
    }

    private Map<String, DbEntry> usableCachedCollection(String projectName, String collName) {
        return userCache.isCachingDisabled(projectName) ? null : completeCachedCollection(projectName, collName);
    }

    public boolean holdsWholeCollection(String projectName, String collName) {
        return usableCachedCollection(projectName, collName) != null;
    }

    public Stream<DbEntry> streamCollection(String projectName, String collName) throws IOException {
        final var cached = usableCachedCollection(projectName, collName);
        return cached != null ? cached.values().stream() : streamCollectionFromDisk(projectName, collName);
    }

    public Stream<DbEntry> streamCollectionSkipping(String projectName, String collName, long skip) throws IOException {
        if (skip <= 0) {
            return streamCollection(projectName, collName);
        }
        final var cached = usableCachedCollection(projectName, collName);
        if (cached != null) {
            return cached.values().stream().skip(skip);
        }
        final var sortedPages = completeSortedPages(projectName, collName);
        if (sortedPages == null || !locks.holdsCollectionLock(projectName, collName)) {
            return streamCollectionFromDisk(projectName, collName).skip(skip);
        }
        final var trusted = fs.trustedLiveRows(projectName, collName);
        final var plan = trusted.isPresent() && !PageSkip.countsMatch(sortedPages, trusted.getAsLong())
                ? null
                : PageSkip.plan(sortedPages, skip);
        if (plan == null) {
            return streamCollectionFromDisk(projectName, collName).skip(skip);
        }
        final var scan = new ScanAdmission(plan.pages(), false, pageReader(projectName, collName), memoryManagement,
                null);
        return StreamSupport.stream(scan, false).skip(plan.remainder());
    }

    public Stream<DbEntry> streamCollectionInScanOrder(String projectName, String collName) throws IOException {
        final var cached = usableCachedCollection(projectName, collName);
        return cached != null
                ? Arrays.stream(scanOrder.inScanOrder(getCollectionIdentifier(projectName, collName), cached))
                : streamCollectionFromDisk(projectName, collName, true);
    }

    private Stream<DbEntry> streamCollectionFromDisk(String projectName, String collName) throws IOException {
        return streamCollectionFromDisk(projectName, collName, false);
    }

    private Stream<DbEntry> streamCollectionFromDisk(String projectName, String collName, boolean inScanOrder)
            throws IOException {
        final var sortedPages = completeSortedPages(projectName, collName);
        if (sortedPages == null) {
            return inScanOrder
                    ? fs.streamPages(projectName, collName).flatMap(page -> sortedById(page.values()))
                    : fs.streamEntries(projectName, collName);
        }
        final var scan = new ScanAdmission(sortedPages, inScanOrder, pageReader(projectName, collName),
                memoryManagement, scanCompletion(projectName, collName, ScanAdmission.liveBytesOf(sortedPages)));
        return StreamSupport.stream(scan, false);
    }

    private List<AdminPageEntry> completeSortedPages(String projectName, String collName) throws IOException {
        final var collPages = adminCache.getAdminPageEntries(projectName, collName);
        if (collPages == null || collPages.isEmpty() || collPages.size() < fs.pageFileCount(projectName, collName)) {
            return null;
        }
        return collPages.stream().sorted(Comparator.comparingLong(AdminPageEntry::getPage)).toList();
    }

    private ScanAdmission.PageReader pageReader(String projectName, String collName) {
        return page -> fs.readWholeCollectionPage(projectName, collName, page).values();
    }

    private ScanAdmission.Completion scanCompletion(String projectName, String collName, long liveBytes) {
        if (userCache.isCachingDisabled(projectName) || !locks.holdsCollectionLock(projectName, collName)) {
            return null;
        }
        final var identifier = getCollectionIdentifier(projectName, collName);
        if (scanRefusals.refusedAt(identifier, freeBudgetBytes(), liveBytes)) {
            return null;
        }
        return new ScanAdmission.Completion() {
            @Override
            public void admit(ConcurrentHashMap<String, DbEntry> collected, long measuredBytes) {
                if (locks.holdsCollectionLock(projectName, collName)
                        && collected.size() == pkIndexSize(projectName, collName)) {
                    userCache.admitScannedCollection(projectName, collName, collected, measuredBytes);
                }
            }

            @Override
            public void refuse(long reachedBytes) {
                scanRefusals.refused(identifier, reachedBytes, liveBytes);
            }
        };
    }

    public long userCacheFootprintBytes() {
        return userCache.footprintBytes();
    }

    private long freeBudgetBytes() {
        final var maxBytes = userCache.maxMemoryBytes();
        return maxBytes == Globals.CACHE_UNLIMITED ? Long.MAX_VALUE : maxBytes - userCache.footprintBytes();
    }

    public void forgetScanRefusal(String projectName, String collName) {
        scanRefusals.forget(projectName + Globals.COLL_IDENTIFIER_SEPARATOR + collName);
    }

    public void forgetScanRefusals(String projectName) {
        scanRefusals.forgetProject(projectName);
    }

    private static Stream<DbEntry> sortedById(Collection<DbEntry> pageDocuments) {
        return pageDocuments.stream().sorted(BY_ID);
    }

    private Map<String, DbEntry> readWholeCollection(String projectName, String collName) throws IOException {
        final var result = new HashMap<String, DbEntry>();
        try (var pagesStream = fs.streamPages(projectName, collName)) {
            pagesStream.forEach(result::putAll);
        }
        return result;
    }

    public boolean mayAdmitFieldIndexLookup(String projectName, String collName, String fieldName, int ids) {
        return locks.holdsCollectionLock(projectName, collName)
                && userCache.wouldAdmit(new AdmissionRequest(AccessKind.FIELD_INDEX, projectName, collName, fieldName,
                        CacheSizeEstimator.estimateFieldIndexLookupSize(ids)));
    }

    public boolean hasNoIndex(String projectName, String collName, String fieldName) {
        return !adminCache.hasIndex(projectName, collName, fieldName)
                || fs.indexMarkers().isMarked(projectName, collName, fieldName);
    }

}
