package org.ccg.mpp.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.ccg.mpp.cache.AccessKind;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.cache.CacheResidents;
import org.ccg.mpp.cache.CacheSizeEstimator;
import org.ccg.mpp.cache.UserCache;
import org.ccg.mpp.data.PkIndexEntry;
import org.junit.jupiter.api.Test;

public class PkIndexEditsChargeTest {
    private static final String PROJECT = "editsProject";
    private static final String COLL = "editsColl";

    private static List<PkIndexEntry> rows(String prefix, int count) {
        final var rows = new ArrayList<PkIndexEntry>();
        for (var i = 0; i < count; i++) {
            rows.add(new PkIndexEntry(PROJECT, COLL, prefix + String.format("%05d", i), i * 10L, 10, 0));
        }
        return rows;
    }

    @Test
    public void pointAndMergedInsertsAndRemovesMoveTheRunningTotal() {
        final var cache = new UserCache();
        final var initial = rows("a", 10);
        final var perRow = CacheSizeEstimator.pkIndexBytesPerEntry(initial);
        final var resident = CacheResidents.residentPk(cache, Cache.getCollectionIdentifier(PROJECT, COLL), initial);
        assertEquals(10 * perRow, CacheResidents.footprint(cache, AccessKind.PK_INDEX));

        PkIndexEdits.insertAll(resident, rows("b", 5));
        assertEquals(15 * perRow, CacheResidents.footprint(cache, AccessKind.PK_INDEX), "point inserts");

        PkIndexEdits.insertAll(resident, rows("c", PkIndexEdits.POINT_EDIT_LIMIT + 1));
        final var afterMerge = 15 + PkIndexEdits.POINT_EDIT_LIMIT + 1;
        assertEquals(afterMerge * perRow, CacheResidents.footprint(cache, AccessKind.PK_INDEX), "a merged insert");

        PkIndexEdits.replaceAll(resident, rows("a", 3));
        assertEquals(afterMerge * perRow, CacheResidents.footprint(cache, AccessKind.PK_INDEX),
                "replacing rows changes no count");

        resident.removeFirst();
        resident.removeLast();
        assertEquals((afterMerge - 2) * perRow, CacheResidents.footprint(cache, AccessKind.PK_INDEX), "removes");
        assertEquals(resident.size(), resident.publishedSize());

        cache.evictPkIndex(PROJECT, COLL);
        assertEquals(0L, CacheResidents.footprint(cache, AccessKind.PK_INDEX));
        resident.addFirst(rows("z", 1).getFirst());
        assertEquals(0L, CacheResidents.footprint(cache, AccessKind.PK_INDEX),
                "a writer still holding an evicted list never charges the total");
    }
}
