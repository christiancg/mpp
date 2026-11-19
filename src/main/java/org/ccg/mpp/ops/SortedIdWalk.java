package org.ccg.mpp.ops;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import org.ccg.mpp.analyze.ReadObserver;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.ioc.IocContainer;

final class SortedIdWalk implements Iterator<String> {
    private static final long GROWTH = 4;
    private static final Cache cache = IocContainer.get(Cache.class);

    private final String projectName;
    private final String collName;
    private final String fieldName;
    private final boolean ascending;
    private List<String> ids;
    private long requested;
    private int at;
    private boolean declined;

    private SortedIdWalk(String projectName, String collName, String fieldName, boolean ascending, List<String> ids,
            long requested) {
        this.projectName = projectName;
        this.collName = collName;
        this.fieldName = fieldName;
        this.ascending = ascending;
        this.ids = ids;
        this.requested = requested;
    }

    static SortedIdWalk open(String projectName, String collName, String fieldName, boolean ascending, long firstPage,
            ReadObserver observer) throws IOException {
        final var ids = IndexHelper.getSortedIdsForField(projectName, collName, fieldName,
                SortOperatorHelper.entryOrder(ascending), firstPage, observer);
        return ids == null ? null : new SortedIdWalk(projectName, collName, fieldName, ascending, ids, firstPage);
    }

    @Override
    public boolean hasNext() {
        if (at < ids.size()) {
            return true;
        }
        if (declined || ids.size() < requested) {
            return false;
        }
        if (cache.reloadsLargeFieldIndex(projectName, collName, fieldName)) {
            declined = true;
            return false;
        }
        requested = Math.multiplyExact(requested, GROWTH);
        try {
            final var longer = IndexHelper.getSortedIdsForField(projectName, collName, fieldName,
                    SortOperatorHelper.entryOrder(ascending), requested, ReadObserver.NONE);
            if (longer == null) {
                declined = true;
                return false;
            }
            ids = longer;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return at < ids.size();
    }

    boolean declined() {
        return declined;
    }

    @Override
    public String next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        return ids.get(at++);
    }
}
