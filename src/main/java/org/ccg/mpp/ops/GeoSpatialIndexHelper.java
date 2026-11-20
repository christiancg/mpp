package org.ccg.mpp.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.ccg.mpp.analyze.AnalyzeContext;
import org.ccg.mpp.analyze.ReadObserver;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.FieldIndexEntry;
import org.ccg.mpp.ejson.custom_types.GeoDistanceComparator;
import org.ccg.mpp.ejson.custom_types.JsonGeo;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.index.PendingWriteReconciler;
import org.ccg.mpp.ops.req.agg.operators.CustomOperator;
import org.ccg.mpp.utils.GeoPoint;
import org.ccg.mpp.utils.GeoUtils;

// The geohash-sorted index yields candidate ids only; the caller (FilterOperatorHelper) re-tests each
// fetched document exactly, so the pre-filter can never change the result - only reduce document reads.
public final class GeoSpatialIndexHelper {
    private GeoSpatialIndexHelper() {
    }

    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ResourceLocking rl = IocContainer.get(ResourceLocking.class);

    public static Set<String> candidateIds(CustomOperator operator, String projectName, String collName,
            ReadObserver observer) throws IOException {
        final var bbox = boundingBoxFor(operator);
        if (bbox == null) {
            return null;
        }
        if (cache.hasNoIndex(projectName, collName, operator.getField())) {
            return null;
        }
        final var fieldName = operator.getField();
        final var pendingBefore = PendingWriteReconciler.pendingIds(projectName, collName);
        final Set<String> candidates;
        try {
            rl.lockIndexRead(projectName, collName, fieldName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while acquiring index read lock", e);
        }
        try {
            final var entries = cache.getFieldIndexAndLoadIfNecessary(projectName, collName, fieldName, JsonGeo.class);
            if (entries == null || entries.isEmpty()) {
                return null;
            }
            candidates = collectByBoundingBox(entries, bbox);
        } finally {
            rl.releaseIndexRead(projectName, collName, fieldName);
        }
        recordAnalyzeIndexUse(projectName, collName, fieldName, observer);
        if (!Globals.ADMIN_PROJECT_NAME.equals(projectName)) {
            cache.recordFieldIndexAccess(projectName, collName, fieldName);
        }
        // Not-yet-indexed committed writes are added as candidates; the caller re-tests exactly.
        candidates.addAll(PendingWriteReconciler.pendingIdsAround(pendingBefore, projectName, collName));
        return candidates;
    }

    private static GeoUtils.BoundingBox boundingBoxFor(CustomOperator operator) {
        final var name = operator.getCustomOperatorName();
        if (JsonGeo.OPERATOR_WITHIN.equals(name)) {
            final var polygonArray = operator.getArgs().get("polygon").asJsonArray();
            final var polygon = new ArrayList<GeoPoint>();
            for (var vertex : polygonArray.asList()) {
                polygon.add(((JsonGeo) vertex).point());
            }
            return GeoUtils.boundingBoxOf(polygon);
        }
        if (JsonGeo.OPERATOR_DISTANCE.equals(name)) {
            final var comparator = GeoDistanceComparator
                    .valueOf(operator.getArgs().get("comparator").asJsonString().getValue());
            // Only "within radius" is a contiguous range; "farther than" selects outside the box, so scan.
            if (comparator != GeoDistanceComparator.SMALLER_THAN
                    && comparator != GeoDistanceComparator.SMALLER_THAN_EQUALS) {
                return null;
            }
            final var target = ((JsonGeo) operator.getValue()).point();
            final var distance = operator.getArgs().get("distance").asJsonNumber().getValue().doubleValue();
            return GeoUtils.boundingBoxForRadius(target, distance);
        }
        return null;
    }

    private static Set<String> collectByBoundingBox(List<FieldIndexEntry<JsonGeo>> entries, GeoUtils.BoundingBox bbox) {
        final var ids = new HashSet<String>();
        for (var prefix : GeoUtils.coveringGeohashPrefixes(bbox)) {
            final var from = GeoUtils.prefixBits(prefix, JsonGeo.GEO_HASH_ORDER_PRECISION);
            final var to = from + GeoUtils.prefixWidth(prefix, JsonGeo.GEO_HASH_ORDER_PRECISION);
            var i = lowerBound(entries, from);
            while (i < entries.size() && entries.get(i).getValue().orderKey() < to) {
                final var geo = entries.get(i).getValue();
                if (bbox.contains(geo.point())) {
                    ids.addAll(entries.get(i).getIds());
                }
                i++;
            }
        }
        return ids;
    }

    private static int lowerBound(List<FieldIndexEntry<JsonGeo>> entries, long from) {
        var lo = 0;
        var hi = entries.size();
        while (lo < hi) {
            final var mid = (lo + hi) >>> 1;
            if (entries.get(mid).getValue().orderKey() < from) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private static void recordAnalyzeIndexUse(String projectName, String collName, String fieldName,
            ReadObserver observer) {
        observer.indexUsed(fieldName, AnalyzeContext.fieldLockId(projectName, collName, fieldName));
    }
}
