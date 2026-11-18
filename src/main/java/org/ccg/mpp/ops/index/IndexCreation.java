package org.ccg.mpp.ops.index;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.ConfigKey;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.CollectionReadinessGuard;
import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.IndexHelper;
import org.ccg.mpp.ops.OnDiskNameRegistry;
import org.ccg.mpp.ops.OperationLocks;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.req.CreateIndexRequest;
import org.ccg.mpp.ops.resp.OperationResponse;

final class IndexCreation {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final Configuration configuration = Configuration.getInstance();

    private IndexCreation() {
    }

    static OperationResponse process(CreateIndexRequest request) {
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        final var targets = request.targetFieldNames();
        final var tooMany = tooManyIndexes(targets.size());
        if (tooMany != null) {
            return tooMany;
        }
        return OperationLocks.withCollectionLock(dbName, collName, OperationType.CREATE_INDEX,
                ErrorCode.ERROR_CREATING_INDEX, () -> {
                    final var readinessError = CollectionReadinessGuard.check(OperationType.CREATE_INDEX, dbName,
                            collName);
                    if (readinessError != null) {
                        return readinessError;
                    }
                    final var missing = new ArrayList<String>();
                    for (final var fieldName : targets) {
                        if (cache.hasNoIndex(dbName, collName, fieldName)) {
                            missing.add(fieldName);
                        }
                    }
                    if (missing.isEmpty()) {
                        return OperationResponse.ok(OperationType.CREATE_INDEX,
                                "Index already exists for " + fieldsLabel(targets));
                    }
                    final var refusal = refusalOf(dbName, collName, missing);
                    if (refusal != null) {
                        return refusal;
                    }
                    for (final var fieldName : missing) {
                        fs.indexMarkers().mark(dbName, collName, fieldName);
                    }
                    IndexHelper.createIndexes(dbName, collName, missing);
                    for (final var fieldName : missing) {
                        AdminOperationHelper.saveNewIndex(dbName, collName, fieldName);
                        fs.indexMarkers().clear(dbName, collName, fieldName);
                    }
                    return OperationResponse.ok(OperationType.CREATE_INDEX, createdMessage(targets, missing));
                });
    }

    private static OperationResponse refusalOf(String dbName, String collName, List<String> missing) {
        final var tooMany = tooManyIndexes(cache.getIndexesForCollection(dbName, collName).size() + missing.size());
        if (tooMany != null) {
            return tooMany;
        }
        final var byOnDiskKey = new HashMap<String, String>();
        for (final var fieldName : missing) {
            var colliding = OnDiskNameRegistry.collidingIndexField(dbName, collName, fieldName);
            if (colliding == null) {
                final var earlier = byOnDiskKey.putIfAbsent(OnDiskNameRegistry.onDiskKey(fieldName), fieldName);
                colliding = earlier == null || earlier.equals(fieldName) ? null : earlier;
            }
            if (colliding != null) {
                return new OperationResponse(OperationType.CREATE_INDEX, ErrorCode.NAME_COLLIDES_ON_DISK, colliding);
            }
        }
        return null;
    }

    private static OperationResponse tooManyIndexes(int indexCount) {
        final var maxIndexes = configuration.intValue(ConfigKey.MAX_INDEXES_PER_COLLECTION);
        if (indexCount <= maxIndexes) {
            return null;
        }
        return new OperationResponse(OperationType.CREATE_INDEX,
                ErrorCode.TOO_MANY_INDEXES_FOR_COLLECTION.getDefaultMessage() + " (" + maxIndexes + ")",
                ErrorCode.TOO_MANY_INDEXES_FOR_COLLECTION);
    }

    private static String createdMessage(List<String> targets, List<String> missing) {
        final var created = "Created index for " + fieldsLabel(missing);
        if (missing.size() == targets.size()) {
            return created;
        }
        final var existing = new ArrayList<>(targets);
        existing.removeAll(missing);
        return created + "; already existed for " + fieldsLabel(existing);
    }

    private static String fieldsLabel(List<String> fieldNames) {
        return fieldNames.size() == 1 ? "field: " + fieldNames.getFirst() : "fields: " + String.join(", ", fieldNames);
    }
}
