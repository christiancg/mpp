package org.ccg.mpp.ops.admin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.ccg.mpp.bckg_ops.events.EntityRef;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.data.admin.AdminPageEntry;
import org.ccg.mpp.ioc.IocContainer;

final class WritePathPageBooking {
    private static final Cache cache = IocContainer.get(Cache.class);

    private final String projectName;
    private final String collName;
    private final Set<Long> existing = new LinkedHashSet<>();
    private final Map<Long, AdminPageEntry> missing = new LinkedHashMap<>();

    private WritePathPageBooking(String projectName, String collName) {
        this.projectName = projectName;
        this.collName = collName;
    }

    static void book(String projectName, String collName, List<EntityRef> created, List<EntityRef> others,
            long incarnation) {
        final var booking = new WritePathPageBooking(projectName, collName);
        booking.created(created);
        booking.others(others);
        cache.addAdminPageEntries(projectName, collName, new ArrayList<>(booking.missing.values()));
        PageRowWriteBack.touched(projectName, collName, incarnation, booking.touchedPages(),
                System.currentTimeMillis());
    }

    private void created(List<EntityRef> refs) {
        final var seen = HashSet.<String>newHashSet(refs.size());
        for (final var ref : refs) {
            if (!seen.add(ref.id()) || isKnownRow(ref.page())) {
                continue;
            }
            final var row = missing.computeIfAbsent(ref.page(),
                    page -> new AdminPageEntry(projectName, collName, page));
            row.setEntryCount(row.getEntryCount() + 1);
            row.setPageSize(row.getPageSize() + ref.byteSize());
        }
    }

    private void others(List<EntityRef> refs) {
        for (final var ref : refs) {
            isKnownRow(ref.page());
        }
    }

    private boolean isKnownRow(long page) {
        if (existing.contains(page)) {
            return true;
        }
        if (missing.containsKey(page) || cache.getAdminPageEntry(projectName, collName, page) == null) {
            return false;
        }
        existing.add(page);
        return true;
    }

    private List<Long> touchedPages() {
        final var pages = new ArrayList<Long>(existing.size() + missing.size());
        pages.addAll(existing);
        pages.addAll(missing.keySet());
        return pages;
    }
}
