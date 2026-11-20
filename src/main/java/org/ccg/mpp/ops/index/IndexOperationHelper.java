package org.ccg.mpp.ops.index;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.ccg.mpp.bckg_ops.PendingIndexWrites;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.data.PkIndexEntry;
import org.ccg.mpp.ex.PkRebuildRefusedException;
import org.ccg.mpp.fault.FaultInjector;
import org.ccg.mpp.fault.FaultPoint;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.fs.PkRebuild;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.IndexHelper;
import org.ccg.mpp.ops.OperationLocks;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.admin.PageOccupancyReconciler;
import org.ccg.mpp.ops.req.CreateIndexRequest;
import org.ccg.mpp.ops.req.DropIndexRequest;
import org.ccg.mpp.ops.req.ReindexRequest;
import org.ccg.mpp.ops.resp.OperationResponse;
import org.ccg.mpp.ops.resp.PrimaryKeyReindexResponse;
import org.ccg.mpp.ops.resp.ReindexResponse;
import org.ccg.mpp.ops.snapshot.SnapshotRegistry;

// Index files are built and the field registered in admin metadata under one held collection write lock,
// which stops a save landing mid-build from being skipped as "not a known index".
public final class IndexOperationHelper {
    private static final Cache cache = IocContainer.get(Cache.class);
    @SuppressWarnings("FieldMayBeFinal")
    private static FileSystem fs = IocContainer.get(FileSystem.class);
    private static final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);
    private static final FaultInjector faultInjector = IocContainer.get(FaultInjector.class);
    private static final SnapshotRegistry snapshots = IocContainer.get(SnapshotRegistry.class);

    private IndexOperationHelper() {
    }

    public static OperationResponse processCreateIndex(CreateIndexRequest createIndexRequest) {
        return IndexCreation.process(createIndexRequest);
    }

    public static OperationResponse processDropIndex(DropIndexRequest dropIndexRequest) {
        final var projectName = dropIndexRequest.getProjectName();
        final var collName = dropIndexRequest.getCollectionName();
        final var fieldName = dropIndexRequest.getFieldName();
        // The collection write lock makes the file deletion and the unregistration atomic with respect to
        // saves and the background indexer.
        return OperationLocks.withCollectionLock(projectName, collName, OperationType.DROP_INDEX,
                ErrorCode.ERROR_DROPPING_INDEX, () -> {
                    if (cache.getIndexesForCollection(projectName, collName).contains(fieldName)) {
                        fs.indexMarkers().mark(projectName, collName, fieldName);
                    }
                    final var result = IndexHelper.dropIndex(projectName, collName, fieldName);
                    if (result) {
                        AdminOperationHelper.deleteIndex(projectName, collName, fieldName);
                        fs.indexMarkers().clear(projectName, collName, fieldName);
                        return OperationResponse.ok(OperationType.DROP_INDEX,
                                "Successfully dropped index: " + fieldName);
                    } else {
                        return new OperationResponse(OperationType.DROP_INDEX, ErrorCode.INDEX_NOT_FOUND, fieldName);
                    }
                });
    }

    public static OperationResponse processReindex(ReindexRequest request) {
        return processReindex(request, false);
    }

    public static OperationResponse processReindex(ReindexRequest request, boolean bounded) {
        final var projectName = request.getProjectName();
        final var collName = request.getCollectionName();
        return OperationLocks.withCollectionLock(projectName, collName, OperationType.REINDEX,
                ErrorCode.ERROR_REINDEXING, bounded, () -> {
                    final var registeredIndexes = cache.getIndexesForCollection(projectName, collName);
                    for (var fieldName : request.getFieldNames()) {
                        if (!registeredIndexes.contains(fieldName)) {
                            return new OperationResponse(OperationType.REINDEX, ErrorCode.INDEX_NOT_FOUND, fieldName);
                        }
                    }
                    if (request.isPrimaryKey()) {
                        return reindexWithPrimaryKey(request, registeredIndexes);
                    }
                    final var targets = fieldTargets(request, registeredIndexes, null);
                    rebuildFieldIndexes(projectName, collName, targets, registeredIndexes);
                    if (targets.isEmpty()) {
                        return new ReindexResponse("No indexes to rebuild", List.of());
                    }
                    return new ReindexResponse("Rebuilt " + targets.size() + " index(es)", targets);
                });
    }

    private static OperationResponse reindexWithPrimaryKey(ReindexRequest request, Set<String> registeredIndexes)
            throws Exception {
        final var projectName = request.getProjectName();
        final var collName = request.getCollectionName();
        final PkRebuild rebuild;
        cache.userCache().evictCollection(projectName, collName);
        try {
            rebuild = fs.planPkRebuild(projectName, collName);
        } catch (PkRebuildRefusedException refused) {
            return new OperationResponse(OperationType.REINDEX, ErrorCode.PRIMARY_KEY_REBUILD_REFUSED,
                    refused.getMessage());
        }
        final var targets = fieldTargets(request, registeredIndexes, rebuild);
        recordFieldIndexWork(projectName, collName, rebuild, targets);
        snapshots.abortCollection(projectName, collName, "the primary-key index of " + collName + " was rebuilt");
        fs.applyPkRebuild(projectName, collName, rebuild);
        faultInjector.at(FaultPoint.PK_INDEX_REPLACED, Cache.getCollectionIdentifier(projectName, collName));
        cache.userCache().evictPkIndex(projectName, collName);
        cache.userCache().evictCollectionDocuments(projectName, collName);
        PageOccupancyReconciler.rebuildRows(projectName, collName);
        rebuildFieldIndexes(projectName, collName, targets, registeredIndexes);
        final var rebuiltFields = targets.isEmpty() ? "" : " and " + targets.size() + " index(es)";
        return new PrimaryKeyReindexResponse(
                "Rebuilt the primary-key index (" + rebuild.entries() + " entries)" + rebuiltFields, targets, rebuild);
    }

    private static void recordFieldIndexWork(String projectName, String collName, PkRebuild rebuild,
            List<String> targets) throws IOException {
        for (var fieldName : targets) {
            fs.indexMarkers().mark(projectName, collName, fieldName);
        }
        if (!rebuild.previousReadable()) {
            return;
        }
        if (!rebuild.appeared().isEmpty()) {
            PageOccupancyReconciler.scheduleIndexRepairOf(projectName, collName,
                    rebuild.appeared().stream().map(PkIndexEntry::getValue).toList());
        }
        PageOccupancyReconciler.scheduleIndexCleanupFor(rebuild.vanished());
    }

    private static List<String> fieldTargets(ReindexRequest request, Set<String> registeredIndexes, PkRebuild rebuild) {
        final var everyIndex = new ArrayList<>(registeredIndexes);
        if (rebuild == null) {
            return request.getFieldNames().isEmpty() ? everyIndex : request.getFieldNames();
        }
        return rebuild.previousReadable() ? request.getFieldNames() : everyIndex;
    }

    private static void rebuildFieldIndexes(String projectName, String collName, List<String> targets,
            Set<String> registeredIndexes) throws IOException, InterruptedException {
        for (var fieldName : targets) {
            fs.indexMarkers().mark(projectName, collName, fieldName);
        }
        for (var fieldName : targets) {
            IndexHelper.dropIndex(projectName, collName, fieldName);
        }
        IndexBuild.buildAll(projectName, collName, targets);
        for (var fieldName : targets) {
            fs.indexMarkers().clear(projectName, collName, fieldName);
        }
        clearDirtyMarkerIfFullyRebuilt(projectName, collName, targets, registeredIndexes);
    }

    private static void clearDirtyMarkerIfFullyRebuilt(String projectName, String collName, List<String> rebuiltFields,
            Set<String> registeredIndexes) {
        if (Set.copyOf(rebuiltFields).containsAll(registeredIndexes)) {
            pendingIndexWrites.clearCollection(projectName, collName);
            fs.indexMarkers().retireDirty(projectName, collName);
            fs.indexMarkers().clearUncleanStop(projectName, collName);
        }
    }
}
