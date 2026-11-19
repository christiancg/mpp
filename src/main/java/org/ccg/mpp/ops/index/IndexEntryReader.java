package org.ccg.mpp.ops.index;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.ccg.mpp.analyze.AnalyzeContext;
import org.ccg.mpp.analyze.ReadObserver;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.cache.IndexIdCounts;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.FieldIndexEntry;
import org.ccg.mpp.data.IndexKind;
import org.ccg.mpp.data.SortedChunks;
import org.ccg.mpp.ejson.custom_types.CustomTypeFactory;
import org.ccg.mpp.ejson.elements.JsonBaseElement;
import org.ccg.mpp.ejson.elements.JsonBoolean;
import org.ccg.mpp.ejson.elements.JsonNull;
import org.ccg.mpp.ejson.elements.JsonNumber;
import org.ccg.mpp.ejson.elements.JsonPrimitive;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.utils.CaseFolding;
import org.ccg.mpp.utils.FieldPath;
import org.ccg.mpp.utils.SaltedKey;

public final class IndexEntryReader {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ResourceLocking rl = IocContainer.get(ResourceLocking.class);
    private static final IndexIdCounts idCounts = IocContainer.get(IndexIdCounts.class);

    private IndexEntryReader() {
    }

    // Entries are read under the field's index read lock and returned as deep copies, so the background
    // index writer can never mutate a set a consumer is still iterating.
    public static List<FieldIndexEntry<?>> getIndexEntriesForField(String projectName, String collName,
            String fieldName, ReadObserver observer) throws IOException {
        if (cache.hasNoIndex(projectName, collName, fieldName)) {
            return null;
        }
        final List<FieldIndexEntry<?>> combined = new ArrayList<>();
        final Set<String> hashIds = new HashSet<>();
        final var pendingBefore = PendingWriteReconciler.pendingIds(projectName, collName);
        try {
            rl.lockIndexRead(projectName, collName, fieldName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while acquiring index read lock", e);
        }
        try {
            for (var type : IndexKind.scalarIndexClasses()) {
                if (!addEntriesOfType(combined, projectName, collName, fieldName, type)) {
                    return null;
                }
            }
            for (var kind : IndexKind.HASH_KINDS) {
                final var hashEntries = cache.getHashIndexAndLoadIfNecessary(projectName, collName, fieldName, kind);
                if (hashEntries != null) {
                    for (var entry : hashEntries) {
                        hashIds.addAll(entry.getIds());
                    }
                }
            }
        } finally {
            rl.releaseIndexRead(projectName, collName, fieldName);
        }
        if (!hashIds.isEmpty()) {
            addHashIndexEntries(combined, projectName, collName, fieldName, hashIds, observer);
        }
        final var pendingIds = PendingWriteReconciler.pendingIdsAround(pendingBefore, projectName, collName);
        if (!pendingIds.isEmpty()
                && !reconcilePending(combined, projectName, collName, fieldName, pendingIds, observer)) {
            return null;
        }
        if (combined.isEmpty() || doesNotCoverEveryDocument(combined, projectName, collName)) {
            return null;
        }
        if (!Globals.ADMIN_PROJECT_NAME.equals(projectName)) {
            cache.recordFieldIndexAccess(projectName, collName, fieldName);
        }
        recordAnalyzeIndexUse(projectName, collName, fieldName, observer);
        return combined;
    }

    public static List<String> sortedIdsForField(String projectName, String collName, String fieldName,
            Comparator<FieldIndexEntry<?>> order, long maxIds, ReadObserver observer) throws IOException {
        if (cache.hasNoIndex(projectName, collName, fieldName)
                || !PendingWriteReconciler.pendingIds(projectName, collName).isEmpty()) {
            return null;
        }
        try {
            rl.lockIndexRead(projectName, collName, fieldName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while acquiring index read lock", e);
        }
        final List<String> ordered;
        try {
            for (var kind : IndexKind.HASH_KINDS) {
                if (cache.getHashIndexAndLoadIfNecessary(projectName, collName, fieldName, kind) != null) {
                    return null;
                }
            }
            final var typeLists = new ArrayList<List<FieldIndexEntry<?>>>();
            var covered = 0L;
            for (var type : IndexKind.scalarIndexClasses()) {
                final var cached = cache.getFieldIndexAndLoadIfNecessary(projectName, collName, fieldName, type);
                if (cached != null && IndexKind.keysCandidatesOnly(type)) {
                    return null;
                }
                if (cached != null && !cached.isEmpty()) {
                    final var entries = cast(cached);
                    typeLists.add(entries);
                    covered += idCounts.count(projectName, collName, Cache.memoizedIndexIdentifier(fieldName, type),
                            () -> idCount(entries));
                }
            }
            if (typeLists.isEmpty() || coversFewer(covered, projectName, collName)) {
                return null;
            }
            final var walked = SortedIdWalk.idsInOrder(typeLists, order, maxIds);
            ordered = walked != null ? walked : copiedAndSorted(typeLists, order, maxIds);
        } finally {
            rl.releaseIndexRead(projectName, collName, fieldName);
        }
        if (!PendingWriteReconciler.pendingIds(projectName, collName).isEmpty()) {
            return null;
        }
        if (!Globals.ADMIN_PROJECT_NAME.equals(projectName)) {
            cache.recordFieldIndexAccess(projectName, collName, fieldName);
        }
        recordAnalyzeIndexUse(projectName, collName, fieldName, observer);
        return ordered;
    }

    @SuppressWarnings("unchecked")
    private static List<FieldIndexEntry<?>> cast(List<? extends FieldIndexEntry<?>> entries) {
        return (List<FieldIndexEntry<?>>) entries;
    }

    private static List<String> copiedAndSorted(List<List<FieldIndexEntry<?>>> typeLists,
            Comparator<FieldIndexEntry<?>> order, long maxIds) {
        final var entries = new ArrayList<FieldIndexEntry<?>>();
        typeLists.forEach(entries::addAll);
        entries.sort(order);
        return idsInOrder(entries, order, maxIds);
    }

    private static boolean doesNotCoverEveryDocument(List<FieldIndexEntry<?>> entries, String projectName,
            String collName) {
        return coversFewer(idCount(entries), projectName, collName);
    }

    private static boolean coversFewer(long covered, String projectName, String collName) {
        final var documents = cache.pkCountForCoverage(projectName, collName);
        return documents < 0 || covered < documents;
    }

    private static long idCount(List<? extends FieldIndexEntry<?>> entries) {
        if (entries instanceof SortedChunks<?> chunked) {
            @SuppressWarnings("unchecked")
            final var chunks = (SortedChunks<FieldIndexEntry<?>>) chunked;
            return chunks.sumOf(0, entries.size(), entry -> entry.getIds().size());
        }
        var count = 0L;
        for (final var entry : entries) {
            count += entry.getIds().size();
        }
        return count;
    }

    public static List<Object> distinctValuesForField(String projectName, String collName, String fieldName,
            ReadObserver observer) throws IOException {
        if (cache.hasNoIndex(projectName, collName, fieldName)) {
            return null;
        }
        final var pendingBefore = PendingWriteReconciler.pendingIds(projectName, collName);
        if (!pendingBefore.isEmpty()) {
            return valuesOf(getIndexEntriesForField(projectName, collName, fieldName, observer));
        }
        final var values = new ArrayList<>();
        final var hashIds = new HashSet<String>();
        var covered = 0L;
        try {
            rl.lockIndexRead(projectName, collName, fieldName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while acquiring index read lock", e);
        }
        try {
            for (var type : IndexKind.scalarIndexClasses()) {
                final var entries = cache.getFieldIndexAndLoadIfNecessary(projectName, collName, fieldName, type);
                if (entries != null && IndexKind.keysCandidatesOnly(type)) {
                    return null;
                }
                if (entries != null) {
                    covered += idCount(entries);
                    entries.forEach(entry -> values.add(entry.getValue()));
                }
            }
            for (var kind : IndexKind.HASH_KINDS) {
                final var hashEntries = cache.getHashIndexAndLoadIfNecessary(projectName, collName, fieldName, kind);
                if (hashEntries != null) {
                    hashEntries.forEach(entry -> hashIds.addAll(entry.getIds()));
                }
            }
        } finally {
            rl.releaseIndexRead(projectName, collName, fieldName);
        }
        final var hashed = new ArrayList<FieldIndexEntry<?>>();
        if (!hashIds.isEmpty()) {
            addHashIndexEntries(hashed, projectName, collName, fieldName, hashIds, observer);
        }
        if (!PendingWriteReconciler.pendingIdsAround(pendingBefore, projectName, collName).isEmpty()) {
            return valuesOf(getIndexEntriesForField(projectName, collName, fieldName, observer));
        }
        covered += idCount(hashed);
        hashed.forEach(entry -> values.add(entry.getValue()));
        if (values.isEmpty() || coversFewer(covered, projectName, collName)) {
            return null;
        }
        if (!Globals.ADMIN_PROJECT_NAME.equals(projectName)) {
            cache.recordFieldIndexAccess(projectName, collName, fieldName);
        }
        recordAnalyzeIndexUse(projectName, collName, fieldName, observer);
        return values;
    }

    private static List<Object> valuesOf(List<FieldIndexEntry<?>> entries) {
        return entries == null ? null : entries.stream().<Object>map(FieldIndexEntry::getValue).toList();
    }

    public static List<String> idsInOrder(List<FieldIndexEntry<?>> sortedEntries, Comparator<FieldIndexEntry<?>> order,
            long maxIds) {
        final var ordered = new ArrayList<String>();
        var runStart = 0;
        while (runStart < sortedEntries.size() && ordered.size() < maxIds) {
            var runEnd = runStart + 1;
            while (runEnd < sortedEntries.size()
                    && order.compare(sortedEntries.get(runStart), sortedEntries.get(runEnd)) == 0) {
                runEnd++;
            }
            ordered.addAll(mergedIds(sortedEntries.subList(runStart, runEnd)));
            runStart = runEnd;
        }
        return ordered;
    }

    private static List<String> mergedIds(List<FieldIndexEntry<?>> run) {
        if (run.size() == 1) {
            return sortedIds(run.getFirst());
        }
        final var ids = new ArrayList<String>();
        for (final var entry : run) {
            ids.addAll(entry.getIds());
        }
        Collections.sort(ids);
        return ids;
    }

    public static List<String> sortedIds(FieldIndexEntry<?> entry) {
        final var ids = new ArrayList<>(entry.getIds());
        Collections.sort(ids);
        return ids;
    }

    private static void recordAnalyzeIndexUse(String projectName, String collName, String fieldName,
            ReadObserver observer) {
        observer.indexUsed(fieldName, AnalyzeContext.fieldLockId(projectName, collName, fieldName));
    }

    private static void addHashIndexEntries(List<FieldIndexEntry<?>> combined, String projectName, String collName,
            String fieldName, Set<String> hashIds, ReadObserver observer) throws IOException {
        final var docs = cache.getEntriesByIds(projectName, collName, hashIds);
        observer.scanned(docs.size());
        final var byValue = new HashMap<SaltedKey, FieldIndexEntry<JsonBaseElement>>();
        final var path = FieldPath.of(fieldName);
        for (var doc : docs) {
            final var data = doc.getData();
            final var value = path.resolve(data);
            if (value == null) {
                continue;
            }
            if (!value.isJsonObject() && !value.isJsonArray()) {
                continue;
            }
            byValue.computeIfAbsent(new SaltedKey(value), key -> {
                final var entry = new FieldIndexEntry<>(projectName, collName, key.element(), new HashSet<>());
                combined.add(entry);
                return entry;
            }).getIds().add(doc.get_id());
        }
    }

    private static boolean addEntriesOfType(List<FieldIndexEntry<?>> combined, String projectName, String collName,
            String fieldName, Class<?> type) throws IOException {
        final var entries = cache.getFieldIndexAndLoadIfNecessary(projectName, collName, fieldName, type);
        if (entries == null) {
            return true;
        }
        if (IndexKind.keysCandidatesOnly(type)) {
            return false;
        }
        for (var entry : entries) {
            combined.add(new FieldIndexEntry<>(entry.getProjectName(), entry.getCollectionName(), entry.getValue(),
                    new HashSet<>(entry.getIds())));
        }
        return true;
    }

    // Pending ids are dropped from every entry and re-added under the document's CURRENT value; a
    // non-scalar pending value returns false so the caller falls back to a full scan.
    private static boolean reconcilePending(List<FieldIndexEntry<?>> combined, String projectName, String collName,
            String fieldName, Set<String> pendingIds, ReadObserver observer) throws IOException {
        for (var entry : combined) {
            entry.getIds().removeAll(pendingIds);
        }
        final var byValue = new HashMap<SaltedKey, FieldIndexEntry<?>>();
        for (var entry : combined) {
            byValue.put(new SaltedKey(IndexValueCodec.indexValueToElement(entry.getValue())), entry);
        }
        final var path = FieldPath.of(fieldName);
        for (var dbEntry : PendingWriteReconciler.pendingDocuments(projectName, collName, pendingIds, observer)) {
            final var data = dbEntry.getData();
            final var element = path.resolve(data);
            if (element == null) {
                continue;
            }
            if (!element.isJsonPrimitive() && !element.isJsonNull()) {
                return false;
            }
            if (element.isJsonNull()) {
                final var nullEntry = byValue.get(new SaltedKey(JsonNull.INSTANCE));
                if (nullEntry != null) {
                    nullEntry.getIds().add(dbEntry.get_id());
                } else {
                    final var newEntry = new FieldIndexEntry<>(projectName, collName, JsonNull.INSTANCE,
                            new HashSet<>(Set.of(dbEntry.get_id())));
                    combined.add(newEntry);
                    byValue.put(new SaltedKey(JsonNull.INSTANCE), newEntry);
                }
                continue;
            }
            final var existing = byValue.get(new SaltedKey(element));
            if (existing != null) {
                existing.getIds().add(dbEntry.get_id());
            } else {
                final var newEntry = scalarEntryFor(projectName, collName, element.asJsonPrimitive(), dbEntry.get_id());
                combined.add(newEntry);
                byValue.put(new SaltedKey(element), newEntry);
            }
        }
        combined.removeIf(entry -> entry.getIds().isEmpty());
        return true;
    }

    private static FieldIndexEntry<?> scalarEntryFor(String projectName, String collName, JsonPrimitive<?> primitive,
            String id) {
        final var ids = new HashSet<>(Set.of(id));
        return switch (primitive) {
            case JsonNumber jsonNumber ->
                new FieldIndexEntry<>(projectName, collName, jsonNumber.doubleValue() + 0.0, ids);
            case JsonBoolean jsonBoolean -> new FieldIndexEntry<>(projectName, collName, jsonBoolean.getValue(), ids);
            case JsonString jsonString -> jsonString.isJsonCustom()
                    ? new FieldIndexEntry<>(projectName, collName, CustomTypeFactory.getCustomTypeInstance(jsonString),
                            ids)
                    : new FieldIndexEntry<>(projectName, collName, jsonString.getValue(), ids);
            default -> throw new IllegalStateException("Unexpected value: " + primitive);
        };
    }

    public static Set<String> getMatchingIdsForJoin(String projectName, String collName, String fieldName,
            Set<JsonBaseElement> localValues, ReadObserver observer) throws IOException {
        if (cache.hasNoIndex(projectName, collName, fieldName)) {
            return null;
        }
        for (var localValue : localValues) {
            if (IndexValueCodec.elementToLookupValue(localValue) == null) {
                return null;
            }
        }
        recordAnalyzeIndexUse(projectName, collName, fieldName, observer);
        final var pendingBefore = PendingWriteReconciler.pendingIds(projectName, collName);
        final var lookupValues = new ArrayList<>(localValues.size());
        final var foldedStringKeys = new HashSet<String>();
        for (var localValue : localValues) {
            if (localValue instanceof JsonString string && !string.isJsonCustom()
                    && !foldedStringKeys.add(CaseFolding.foldKey(string.getValue()))) {
                continue;
            }
            lookupValues.add(IndexValueCodec.elementToLookupValue(localValue));
        }
        final var matchingIds = cache.getIdsEqualToAny(projectName, collName, fieldName, lookupValues);
        if (matchingIds == null) {
            return null;
        }
        final var pendingIds = PendingWriteReconciler.pendingIdsAround(pendingBefore, projectName, collName);
        if (pendingIds.isEmpty()) {
            return matchingIds;
        }
        final var compiled = FieldPath.of(fieldName);
        return PendingWriteReconciler.correctIds(matchingIds, projectName, collName, pendingIds, fieldName,
                (data, field) -> {
                    final var resolved = compiled.reusedFor(field).resolve(data);
                    return resolved != null && localValues.contains(resolved);
                }, observer);
    }
}
