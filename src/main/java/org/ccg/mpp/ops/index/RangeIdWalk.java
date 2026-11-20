package org.ccg.mpp.ops.index;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import org.ccg.mpp.analyze.AnalyzeContext;
import org.ccg.mpp.analyze.ReadObserver;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.FieldIndexEntry;
import org.ccg.mpp.data.IdSet;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.utils.RangeBounds;
import org.ccg.mpp.utils.SearchUtils;

public final class RangeIdWalk implements Iterator<String> {
    private static final int MAX_PAGE = 4096;
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ResourceLocking rl = IocContainer.get(ResourceLocking.class);

    private final String projectName;
    private final String collName;
    private final RangeBounds bounds;
    private final boolean ascending;
    private int pageSize;
    private List<String> page = List.of();
    private int at;
    private boolean lastPage;
    private boolean started;
    private boolean opened;
    private double lastValue;
    private String lastId;
    private FieldIndexEntry<Number> heldEntry;
    private int heldModifications;
    private List<String> heldIds;

    private RangeIdWalk(String projectName, String collName, RangeBounds bounds, boolean ascending, int firstPage) {
        this.projectName = projectName;
        this.collName = collName;
        this.bounds = bounds;
        this.ascending = ascending;
        this.pageSize = firstPage;
    }

    public static RangeIdWalk open(String projectName, String collName, RangeBounds bounds, boolean ascending,
            long firstPage, ReadObserver observer) throws IOException {
        final var fieldName = bounds.fieldName();
        final var pendingBefore = PendingWriteReconciler.pendingIds(projectName, collName);
        if (!rl.holdsCollectionLock(projectName, collName) || cache.hasNoIndex(projectName, collName, fieldName)
                || !pendingBefore.isEmpty()) {
            return null;
        }
        final var walk = new RangeIdWalk(projectName, collName, bounds, ascending,
                Math.clamp(firstPage, 1, Integer.MAX_VALUE - 8));
        if (!walk.readPage()
                || !PendingWriteReconciler.pendingIdsAround(pendingBefore, projectName, collName).isEmpty()) {
            return null;
        }
        if (!Globals.ADMIN_PROJECT_NAME.equals(projectName)) {
            cache.recordFieldIndexAccess(projectName, collName, fieldName);
        }
        observer.indexUsed(fieldName, AnalyzeContext.fieldLockId(projectName, collName, fieldName));
        return walk;
    }

    @Override
    public boolean hasNext() {
        while (at >= page.size()) {
            if (lastPage) {
                return false;
            }
            if (pageSize < MAX_PAGE) {
                pageSize = Math.min(pageSize * 2, MAX_PAGE);
            }
            try {
                readPage();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return true;
    }

    @Override
    public String next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        return page.get(at++);
    }

    private boolean readPage() throws IOException {
        final var fieldName = bounds.fieldName();
        try {
            rl.lockIndexRead(projectName, collName, fieldName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while acquiring index read lock", e);
        }
        try {
            final var entries = cache.getFieldIndexAndLoadIfNecessary(projectName, collName, fieldName, Number.class);
            page = new ArrayList<>();
            at = 0;
            if (entries == null) {
                if (opened) {
                    throw new IOException("The index on " + fieldName + " of " + projectName + "|" + collName
                            + " could not be read in the middle of a walk");
                }
                lastPage = true;
                return false;
            }
            final var slice = SearchUtils.sliceBetween(entries, bounds);
            if (slice == null) {
                lastPage = true;
            } else {
                collect(entries, slice[0], slice[1]);
            }
            opened = true;
            return true;
        } finally {
            rl.releaseIndexRead(projectName, collName, fieldName);
        }
    }

    private void collect(List<FieldIndexEntry<Number>> entries, int from, int to) {
        final var step = ascending ? 1 : -1;
        var index = started ? resumeIndex(entries, from, to) : ascending ? from : to - 1;
        while (index >= from && index < to && page.size() < pageSize) {
            final var entry = entries.get(index);
            final var value = entry.getValue().doubleValue();
            final var ids = sortedIdsOf(entry);
            var next = 0;
            if (started && FieldIndexEntry.compareIndexedNumbers(value, lastValue) == 0) {
                final var found = Collections.binarySearch(ids, lastId);
                next = found >= 0 ? found + 1 : -found - 1;
            }
            while (next < ids.size() && page.size() < pageSize) {
                lastId = ids.get(next++);
                lastValue = value;
                started = true;
                page.add(lastId);
            }
            if (next < ids.size()) {
                hold(entry, ids);
                return;
            }
            index += step;
        }
        heldEntry = null;
        heldIds = null;
        lastPage = index < from || index >= to;
    }

    private List<String> sortedIdsOf(FieldIndexEntry<Number> entry) {
        if (entry == heldEntry && entry.getIds() instanceof IdSet idSet && idSet.modifications() == heldModifications) {
            return heldIds;
        }
        return IndexEntryReader.sortedIds(entry);
    }

    private void hold(FieldIndexEntry<Number> entry, List<String> ids) {
        if (entry.getIds() instanceof IdSet idSet) {
            heldEntry = entry;
            heldModifications = idSet.modifications();
            heldIds = ids;
        } else {
            heldEntry = null;
            heldIds = null;
        }
    }

    private int resumeIndex(List<FieldIndexEntry<Number>> entries, int from, int to) {
        var low = from;
        var high = to;
        while (low < high) {
            final var mid = (low + high) >>> 1;
            final var compared = FieldIndexEntry.compareIndexedNumbers(entries.get(mid).getValue().doubleValue(),
                    lastValue);
            if (compared < 0 || !ascending && compared == 0) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return ascending ? low : low - 1;
    }
}
