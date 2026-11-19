package org.ccg.mpp.ops.index;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import org.ccg.mpp.analyze.ReadObserver;
import org.ccg.mpp.bckg_ops.PendingIndexWrites;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.data.DbEntry;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ioc.IocContainer;

// Field indexes are maintained asynchronously: an index-backed read must drop the ids still pending and
// re-derive them from current committed values, or a stale entry answers with a false positive.
public final class PendingWriteReconciler {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);

    private PendingWriteReconciler() {
    }

    public static Set<String> pendingIds(String projectName, String collName) {
        return pendingIndexWrites.idsFor(projectName, collName);
    }

    public static Set<String> pendingIdsAround(Set<String> before, String projectName, String collName) {
        final var after = pendingIndexWrites.idsFor(projectName, collName);
        if (before.isEmpty()) {
            return after;
        }
        if (after.isEmpty()) {
            return before;
        }
        final var union = new HashSet<>(before);
        union.addAll(after);
        return union;
    }

    public static List<DbEntry> pendingDocuments(String projectName, String collName, Set<String> pendingIds,
            ReadObserver observer) throws IOException {
        final var documents = cache.getEntriesByIds(projectName, collName, pendingIds);
        observer.scanned(documents.size());
        return documents;
    }

    public static Set<String> correctIds(Set<String> indexed, String projectName, String collName,
            Set<String> pendingIds, String fieldName, BiPredicate<JsonObject, String> matches, ReadObserver observer)
            throws IOException {
        final var corrected = new HashSet<>(indexed);
        corrected.removeAll(pendingIds);
        for (final var dbEntry : pendingDocuments(projectName, collName, pendingIds, observer)) {
            if (matches.test(dbEntry.getData(), fieldName)) {
                corrected.add(dbEntry.get_id());
            }
        }
        return corrected;
    }
}
