package org.ccg.mpp.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.data.Transaction;
import org.ccg.mpp.ejson.elements.JsonArray;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.index.PrimaryKeyIndexResolver;
import org.ccg.mpp.ops.tx.OverlayRead;
import org.ccg.mpp.utils.FieldPath;
import org.ccg.mpp.utils.JsonUtils;
import org.ccg.mpp.utils.SaltedKey;

final class JoinLookup {
    private static final int FETCH_CHUNK = 1024;
    private static final Cache cache = IocContainer.get(Cache.class);

    private JoinLookup() {
    }

    static Map<SaltedKey, JsonArray> build(String projectName, String joinCollectionName, String remoteField,
            List<JsonObject> leftEntries, String localField, Transaction transaction, PipelineContext context)
            throws IOException {
        final var localValues = new SaltedElementSet();
        final var localPath = FieldPath.of(localField);
        for (var left : leftEntries) {
            final var localValue = localPath.resolve(left);
            if (localValue != null) {
                localValues.add(localValue);
            }
        }
        final var overlay = transaction != null
                ? transaction.overlayFor(Cache.getCollectionIdentifier(projectName, joinCollectionName))
                : null;
        if (JsonUtils.isPrimaryKeyPath(remoteField)) {
            final var matchingIds = PrimaryKeyIndexResolver.existingIds(localValues, projectName, joinCollectionName,
                    context);
            return lookupFromMatches(
                    OverlayRead.withBuffered(overlay,
                            fetchMatches(projectName, joinCollectionName, matchingIds, KeptRows.ALL, context)),
                    remoteField, context);
        }
        final var matchingIds = IndexHelper.getMatchingIdsForJoin(projectName, joinCollectionName, remoteField,
                localValues, context);
        if (matchingIds != null) {
            return lookupFromMatches(
                    OverlayRead.withBuffered(overlay,
                            fetchMatches(projectName, joinCollectionName, matchingIds,
                                    new KeptRows(FieldPath.of(remoteField), localValues), context)),
                    remoteField, context);
        }
        if (localValues.isEmpty()) {
            return Map.of();
        }
        final var committed = context.source(null, projectName, joinCollectionName);
        return groupByRemoteField(overlay != null && !overlay.isEmpty()
                ? OverlayRead.reconcile(overlay, committed, _ -> true)
                : committed, remoteField, localValues, context);
    }

    private record KeptRows(FieldPath remotePath, SaltedElementSet localValues) {
        static final KeptRows ALL = new KeptRows(null, null);

        boolean keeps(JsonObject document) {
            if (remotePath() == null) {
                return true;
            }
            final var remoteValue = remotePath.resolve(document);
            return remoteValue != null && localValues.containsKey(new SaltedKey(remoteValue));
        }
    }

    private static List<JsonObject> fetchMatches(String projectName, String joinCollectionName, Set<String> matchingIds,
            KeptRows kept, PipelineContext context) throws IOException {
        if (matchingIds.isEmpty()) {
            return List.of();
        }
        final var held = new HeldRows(context);
        final var documents = new ArrayList<JsonObject>(Math.min(matchingIds.size(), FETCH_CHUNK));
        if (matchingIds.size() <= FETCH_CHUNK) {
            fetchChunk(projectName, joinCollectionName, matchingIds, kept, documents, held, context);
            return documents;
        }
        final var chunk = new HashSet<String>(FETCH_CHUNK * 2);
        for (final var id : matchingIds) {
            chunk.add(id);
            if (chunk.size() == FETCH_CHUNK) {
                fetchChunk(projectName, joinCollectionName, chunk, kept, documents, held, context);
                chunk.clear();
            }
        }
        if (!chunk.isEmpty()) {
            fetchChunk(projectName, joinCollectionName, chunk, kept, documents, held, context);
        }
        return documents;
    }

    private static void fetchChunk(String projectName, String joinCollectionName, Set<String> ids, KeptRows kept,
            List<JsonObject> documents, HeldRows held, PipelineContext context) throws IOException {
        final var matchedDocs = cache.getEntriesByIds(projectName, joinCollectionName, ids, context::pinnedPkIndex);
        context.scanned(matchedDocs.size());
        for (final var entry : matchedDocs) {
            final var data = entry.getData();
            if (kept.keeps(data)) {
                held.hold(data);
                documents.add(data);
            }
        }
    }

    private static Map<SaltedKey, JsonArray> lookupFromMatches(List<JsonObject> matchedDocs, String remoteField,
            PipelineContext context) {
        context.tick(matchedDocs.size());
        final var matchedByKey = new HashMap<SaltedKey, List<JsonObject>>();
        final var remotePath = FieldPath.of(remoteField);
        for (var data : matchedDocs) {
            final var key = remotePath.resolve(data);
            if (key != null) {
                matchedByKey.computeIfAbsent(new SaltedKey(key), _ -> new ArrayList<>()).add(data);
            }
        }
        final var lookup = new HashMap<SaltedKey, JsonArray>();
        matchedByKey.forEach((key, documents) -> lookup.put(key, GroupByOperatorHelper.inIdOrder(documents, context)));
        return lookup;
    }

    private static Map<SaltedKey, JsonArray> groupByRemoteField(Stream<JsonObject> documents, String remoteField,
            SaltedElementSet localValues, PipelineContext context) {
        final var remotePath = FieldPath.of(remoteField);
        final var matched = new HashMap<SaltedKey, List<JsonObject>>();
        final var held = new HeldRows(context);
        try (var remoteDocuments = context.watch(documents)) {
            remoteDocuments.forEach(document -> {
                final var remoteValue = remotePath.resolve(document);
                final var key = remoteValue == null ? null : new SaltedKey(remoteValue);
                if (key != null && localValues.containsKey(key)) {
                    held.hold(document);
                    matched.computeIfAbsent(key, _ -> new ArrayList<>()).add(document);
                }
            });
        }
        final var lookup = new HashMap<SaltedKey, JsonArray>();
        matched.forEach((key, rows) -> lookup.put(key, GroupByOperatorHelper.inIdOrder(rows, context)));
        return lookup;
    }
}
