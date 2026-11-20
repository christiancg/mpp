package org.ccg.mpp.ops;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.ccg.mpp.analyze.ReadObserver;
import org.ccg.mpp.bckg_ops.events.IndexPriors;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.data.DbEntry;
import org.ccg.mpp.data.FieldIndexEntry;
import org.ccg.mpp.ejson.elements.JsonBaseElement;
import org.ccg.mpp.ex.CollectionBusyException;
import org.ccg.mpp.ex.IndexLockBusyException;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.index.CanonicalIds;
import org.ccg.mpp.ops.index.DeferredFold;
import org.ccg.mpp.ops.index.FieldIndexFolds;
import org.ccg.mpp.ops.index.FieldMaintenance;
import org.ccg.mpp.ops.index.IndexBuild;
import org.ccg.mpp.ops.index.IndexEntryReader;
import org.ccg.mpp.ops.index.IndexValueCodec;
import org.ccg.mpp.ops.index.MaintainedIndexes;

public class IndexHelper {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final ResourceLocking rl = IocContainer.get(ResourceLocking.class);
    private static final Configuration configuration = Configuration.getInstance();

    public static List<String> getSortedIdsForField(String projectName, String collName, String fieldName,
            java.util.Comparator<FieldIndexEntry<?>> order, long maxIds, ReadObserver observer) throws IOException {
        return IndexEntryReader.sortedIdsForField(projectName, collName, fieldName, order, maxIds, observer);
    }

    public static List<FieldIndexEntry<?>> getIndexEntriesForField(String projectName, String collName,
            String fieldName, ReadObserver observer) throws IOException {
        return IndexEntryReader.getIndexEntriesForField(projectName, collName, fieldName, observer);
    }

    public static List<Object> getDistinctIndexValues(String projectName, String collName, String fieldName,
            ReadObserver observer) throws IOException {
        return IndexEntryReader.distinctValuesForField(projectName, collName, fieldName, observer);
    }

    public static Set<String> getMatchingIdsForJoin(String projectName, String collName, String fieldName,
            Set<JsonBaseElement> localValues, ReadObserver observer) throws IOException {
        return IndexEntryReader.getMatchingIdsForJoin(projectName, collName, fieldName, localValues, observer);
    }

    public static List<String> idsInOrder(List<FieldIndexEntry<?>> sortedEntries,
            java.util.Comparator<FieldIndexEntry<?>> order, long maxIds) {
        return IndexEntryReader.idsInOrder(sortedEntries, order, maxIds);
    }

    public static JsonBaseElement indexValueToElement(Object value) {
        return IndexValueCodec.indexValueToElement(value);
    }

    public static Object elementToLookupValue(JsonBaseElement element) {
        return IndexValueCodec.elementToLookupValue(element);
    }

    public static void createIndex(String projectName, String collName, String fieldName) throws InterruptedException {
        createIndexes(projectName, collName, List.of(fieldName));
    }

    public static void createIndexes(String projectName, String collName, List<String> fieldNames)
            throws InterruptedException {
        try {
            IndexBuild.buildAll(projectName, collName, fieldNames);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static boolean dropIndex(String projectName, String collName, String fieldName) throws InterruptedException {
        rl.lockIndex(projectName, collName, fieldName);
        try {
            return fs.dropIndex(projectName, collName, fieldName);
        } finally {
            cache.evictFieldIndexAllTypes(projectName, collName, fieldName);
            rl.releaseIndex(projectName, collName, fieldName);
        }
    }

    private static void lockCollectionForMaintenance(String projectName, String collName, long budget)
            throws InterruptedException {
        if (!rl.tryLockRead(projectName, collName, budget)) {
            throw new CollectionBusyException(Cache.getCollectionIdentifier(projectName, collName), budget);
        }
    }

    public static void bulkUpdateIndexes(String projectName, String collName, List<String> ids)
            throws IOException, InterruptedException {
        bulkUpdateIndexes(projectName, collName, ids, configuration.getTransactionLockTimeoutMs());
    }

    public static void bulkUpdateIndexes(String projectName, String collName, List<String> ids, long lockBudgetMs)
            throws IOException, InterruptedException {
        bulkUpdateIndexes(projectName, collName, ids, lockBudgetMs, IndexPriors.NONE);
    }

    public static void bulkUpdateIndexes(String projectName, String collName, List<String> ids, long lockBudgetMs,
            IndexPriors priors) throws IOException, InterruptedException {
        if (ids.isEmpty() || MaintainedIndexes.none(projectName, collName)) {
            return;
        }
        final var folds = new ArrayList<DeferredFold>(0);
        IndexLockBusyException indexBusy = null;
        lockCollectionForMaintenance(projectName, collName, lockBudgetMs);
        try {
            final var existingIndexes = MaintainedIndexes.of(projectName, collName);
            if (existingIndexes.isEmpty()) {
                return;
            }
            final var byId = new HashMap<String, DbEntry>();
            for (var doc : cache.getEntriesByIds(projectName, collName,
                    ids.size() == 1 ? Set.of(ids.getFirst()) : new HashSet<>(ids))) {
                byId.put(doc.get_id(), doc);
            }
            final var canonicalIds = new CanonicalIds(projectName, collName);
            for (var fieldName : existingIndexes) {
                folds.addAll(FieldMaintenance.maintain(projectName, collName, fieldName, ids, byId,
                        priors.forField(fieldName), canonicalIds));
            }
        } catch (IndexLockBusyException busy) {
            indexBusy = busy;
        } finally {
            rl.releaseRead(projectName, collName);
        }
        FieldIndexFolds.runAfterUnlock(folds);
        if (indexBusy != null) {
            throw indexBusy;
        }
    }

    public static void updateIndexes(String projectName, String collName, String id)
            throws IOException, InterruptedException {
        bulkUpdateIndexes(projectName, collName, Collections.singletonList(id));
    }
}
