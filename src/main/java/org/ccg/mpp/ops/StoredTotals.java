package org.ccg.mpp.ops;

import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.ioc.IocContainer;

record StoredTotals(long projects, long collections, long pages, long entries, long sizeBytes) {
    private static final Cache cache = IocContainer.get(Cache.class);
    static final StoredTotals NONE = new StoredTotals(0L, 0L, 0L, 0L, 0L);

    static StoredTotals ofCollection(String projectName, String collName) {
        final var pageEntries = cache.getAdminPageEntries(projectName, collName);
        if (pageEntries == null) {
            return new StoredTotals(0L, 1L, 0L, 0L, 0L);
        }
        long entryCount = 0L;
        long sizeBytes = 0L;
        for (var page : pageEntries) {
            entryCount += page.getEntryCount();
            sizeBytes += page.getPageSize();
        }
        return new StoredTotals(0L, 1L, pageEntries.size(), entryCount, sizeBytes);
    }

    static StoredTotals ofEveryUserProject() {
        var totals = NONE;
        for (var projectName : cache.getUserProjectNames()) {
            var project = new StoredTotals(1L, 0L, 0L, 0L, 0L);
            for (var collName : cache.getCollectionNamesForProject(projectName)) {
                project = project.plus(ofCollection(projectName, collName));
            }
            totals = totals.plus(project);
        }
        return totals;
    }

    StoredTotals plus(StoredTotals other) {
        return new StoredTotals(projects + other.projects, collections + other.collections, pages + other.pages,
                entries + other.entries, sizeBytes + other.sizeBytes);
    }
}
