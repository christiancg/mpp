package org.ccg.mpp.ops;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.ccg.mpp.analyze.AnalyzeContext;
import org.ccg.mpp.analyze.ReadObserver;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.FieldIndexEntry;
import org.ccg.mpp.ejson.custom_types.JsonVector;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.index.PendingWriteReconciler;
import org.ccg.mpp.ops.req.agg.operators.CustomOperator;
import org.ccg.mpp.utils.VectorUtils;

// Locality-sensitive (ANN) pre-filter: it may miss matches outside the scanned neighbourhood, so the
// caller re-scores candidates exactly; an "exact" operator returns null here and forces a full scan.
public final class VectorSimilarityIndexHelper {
    private VectorSimilarityIndexHelper() {
    }

    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ResourceLocking rl = IocContainer.get(ResourceLocking.class);

    private static final int CANDIDATE_MULTIPLIER = 10;
    private static final int MIN_CANDIDATES = 100;

    public static Set<String> candidateIds(CustomOperator operator, String projectName, String collName,
            ReadObserver observer) throws IOException {
        if (!JsonVector.OPERATOR_NEAREST.equals(operator.getCustomOperatorName()) || isExact(operator)) {
            return null;
        }
        if (cache.hasNoIndex(projectName, collName, operator.getField())) {
            return null;
        }
        final var fieldName = operator.getField();
        final var pendingBefore = PendingWriteReconciler.pendingIds(projectName, collName);
        final var query = JsonVector.toVector(operator.getValue());
        final var budget = Math.max(kFor(operator) * CANDIDATE_MULTIPLIER, MIN_CANDIDATES);
        final Set<String> candidates;
        try {
            rl.lockIndexRead(projectName, collName, fieldName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while acquiring index read lock", e);
        }
        try {
            final var entries = cache.getFieldIndexAndLoadIfNecessary(projectName, collName, fieldName,
                    JsonVector.class);
            if (entries == null || entries.isEmpty()) {
                return null;
            }
            candidates = collectNeighbourhood(entries, query, budget);
        } finally {
            rl.releaseIndexRead(projectName, collName, fieldName);
        }
        recordAnalyzeIndexUse(projectName, collName, fieldName, observer);
        if (!Globals.ADMIN_PROJECT_NAME.equals(projectName)) {
            cache.recordFieldIndexAccess(projectName, collName, fieldName);
        }
        // Not-yet-indexed committed writes may be missing from the index; the caller re-scores them exactly.
        candidates.addAll(PendingWriteReconciler.pendingIdsAround(pendingBefore, projectName, collName));
        return candidates;
    }

    private static Set<String> collectNeighbourhood(List<FieldIndexEntry<JsonVector>> entries, double[] query,
            int budget) {
        final var querySignature = VectorUtils.simHashBits(query, JsonVector.SIMHASH_BITS);
        final var start = lowerBound(entries, querySignature);
        final var ids = new HashSet<String>();
        var lo = start - 1;
        var hi = start;
        while (ids.size() < budget && (lo >= 0 || hi < entries.size())) {
            if (hi < entries.size()) {
                ids.addAll(entries.get(hi).getIds());
                hi++;
            }
            if (ids.size() >= budget) {
                break;
            }
            if (lo >= 0) {
                ids.addAll(entries.get(lo).getIds());
                lo--;
            }
        }
        return ids;
    }

    private static int lowerBound(List<FieldIndexEntry<JsonVector>> entries, int signature) {
        var lo = 0;
        var hi = entries.size();
        while (lo < hi) {
            final var mid = (lo + hi) >>> 1;
            if (Integer.compareUnsigned(entries.get(mid).getValue().signature(), signature) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private static boolean isExact(CustomOperator operator) {
        final var exact = operator.getArgs().get("exact");
        return exact != null && exact.isJsonBoolean() && exact.asJsonBoolean().getValue();
    }

    private static int kFor(CustomOperator operator) {
        return operator.getArgs().get("k").asJsonNumber().getValue().intValue();
    }

    private static void recordAnalyzeIndexUse(String projectName, String collName, String fieldName,
            ReadObserver observer) {
        observer.indexUsed(fieldName, AnalyzeContext.fieldLockId(projectName, collName, fieldName));
    }
}
