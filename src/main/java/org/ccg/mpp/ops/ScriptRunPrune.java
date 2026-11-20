package org.ccg.mpp.ops;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.cluster.ClusterCoordinator;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.log.Logger;
import org.ccg.mpp.ops.resp.DeleteResponse;
import org.ccg.mpp.ops.resp.OperationResponse;

public final class ScriptRunPrune {
    static final int ROWS_PER_CHUNK = 1024;
    private static final String STARTED_AT = "startedAt";
    private static final Comparator<Row> OLDEST_FIRST = Comparator.comparingLong(Row::startedAt).thenComparing(Row::id);
    private static final Comparator<Row> PAGE_ORDER = Comparator.comparingLong(Row::page)
            .thenComparingLong(Row::startedAt).thenComparing(Row::id);

    private static final Logger logger = Logger.logFor(ScriptRunPrune.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    @SuppressWarnings("FieldMayBeFinal")
    private static ClusterCoordinator clusterCoordinator = IocContainer.get(ClusterCoordinator.class);

    private record Row(String id, long page, long startedAt) {
    }

    private ScriptRunPrune() {
    }

    public static int prune(String projectName, long cutoff, long maxRows) throws Exception {
        if (cache.getAdminCollectionEntry(projectName, Globals.SCRIPT_RUNS_COLLECTION_NAME) == null) {
            return 0;
        }
        final var selected = selectRows(projectName, cutoff, maxRows);
        selected.sort(PAGE_ORDER);
        var deleted = 0;
        for (var from = 0; from < selected.size(); from += ROWS_PER_CHUNK) {
            final var ids = new ArrayList<String>(Math.min(ROWS_PER_CHUNK, selected.size() - from));
            for (final var row : selected.subList(from, Math.min(selected.size(), from + ROWS_PER_CHUNK))) {
                ids.add(row.id());
            }
            final var chunk = new int[1];
            final var response = OperationLocks.withCollectionLock(projectName, Globals.SCRIPT_RUNS_COLLECTION_NAME,
                    OperationType.DELETE, ErrorCode.ERROR_DELETING, () -> deleteChunk(projectName, ids, chunk));
            deleted += chunk[0];
            if (response.getStatus() != OperationStatus.OK) {
                logger.warning("Stopped pruning the script run history of '" + projectName + "' after " + deleted
                        + " row(s): " + response.getMessage());
                break;
            }
        }
        if (deleted > 0) {
            logger.info("Pruned " + deleted + " script run history row(s) from '" + projectName + "'");
        }
        return deleted;
    }

    private static List<Row> selectRows(String projectName, long cutoff, long maxRows) throws Exception {
        final var stale = new ArrayList<Row>();
        final var kept = new ArrayList<Row>();
        try (var entries = cache.streamCollection(projectName, Globals.SCRIPT_RUNS_COLLECTION_NAME)) {
            entries.forEach(entry -> {
                final var data = entry.getData();
                final var startedAt = data != null && data.has(STARTED_AT)
                        ? data.get(STARTED_AT).asJsonNumber().getValue().longValue()
                        : Long.MIN_VALUE;
                final var row = new Row(entry.get_id(), entry.getPage(), startedAt);
                (startedAt != Long.MIN_VALUE && startedAt < cutoff ? stale : kept).add(row);
            });
        }
        if (maxRows > 0 && kept.size() > maxRows) {
            kept.sort(OLDEST_FIRST);
            stale.addAll(kept.subList(0, (int) (kept.size() - maxRows)));
        }
        return stale;
    }

    private static OperationResponse deleteChunk(String projectName, List<String> ids, int[] deleted) throws Exception {
        final var collName = Globals.SCRIPT_RUNS_COLLECTION_NAME;
        final var refusal = ClusterWriteHelper.refusalUnderLock(OperationType.DELETE, projectName, collName);
        if (refusal != null) {
            return refusal;
        }
        final var reserved = ClusterWriteHelper.deleteReservedBesideForce(clusterCoordinator, projectName, collName,
                ids);
        if (reserved == null) {
            return new OperationResponse(OperationType.DELETE, ErrorCode.NOT_COLLECTION_OWNER);
        }
        deleted[0] = reserved.removed().size();
        ClusterWriteHelper.replicateReserved(clusterCoordinator, projectName, collName, reserved);
        return new DeleteResponse("Pruned " + deleted[0] + " row(s)");
    }
}
