package org.ccg.mpp.cache;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongUnaryOperator;
import java.util.stream.Collectors;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.PkIndexEntry;
import org.ccg.mpp.data.admin.AdminPageEntry;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.fs.PagePlacement;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.log.Logger;

final class AdminPageCache {
    private static final long INTERRUPTED_LOCK_BUDGET_MS = 250;
    private final Logger logger = Logger.logFor(AdminPageCache.class);
    private final Configuration configuration = Configuration.getInstance();
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final org.ccg.mpp.concurrency.ResourceLocking locks = IocContainer
            .get(org.ccg.mpp.concurrency.ResourceLocking.class);
    private final Map<String, List<AdminPageEntry>> pages = new ConcurrentHashMap<>();
    private final Map<String, List<PkIndexEntry>> pagesPkIndexes = new ConcurrentHashMap<>();

    void loadAdminPagesForCollection(String projectName, String collName) throws IOException {
        final var pagesCollName = AdminPageEntry.pagesCollectionName(projectName, collName);
        final var collId = Cache.getCollectionIdentifier(projectName, collName);
        final var pkIdx = readPagesPkIndexOrEmpty(pagesCollName);
        pagesPkIndexes.put(pagesCollectionKey(pagesCollName), new ArrayList<>(pkIdx));
        final var pageEntries = new ArrayList<AdminPageEntry>();
        final var idPrefix = collId + Globals.COLL_IDENTIFIER_SEPARATOR;
        try (var pagesStream = fs.streamPages(Globals.ADMIN_PAGES_PROJECT_NAME, pagesCollName)) {
            pagesStream.forEach(
                    map -> map.values().stream().filter(e -> e.get_id() != null && e.get_id().startsWith(idPrefix))
                            .map(e -> AdminPageEntry.fromJsonObject(projectName, collName, e.getData()))
                            .forEach(pageEntries::add));
        }
        pages.put(collId, new CopyOnWriteArrayList<>(pageEntries));
        rebuildInMemoryPagesFromPkIndex(pagesCollName, pkIdx);
    }

    private List<PkIndexEntry> readPagesPkIndexOrEmpty(String pagesCollName) {
        try {
            return fs.readWholePkIndexFile(Globals.ADMIN_PAGES_PROJECT_NAME, pagesCollName);
        } catch (IOException e) {
            logger.error("Could not read the page rows of " + pagesCollName + "; starting with none, and the"
                    + " startup reconciliation rebuilds them from the page files", e);
            return List.of();
        }
    }

    void rebuildInMemoryPagesFromPkIndex(String collName, List<PkIndexEntry> pkIdx) {
        final var byPage = pkIdx.stream().collect(Collectors.groupingBy(PkIndexEntry::getPage));
        final var entries = new ArrayList<AdminPageEntry>();
        for (var e : byPage.entrySet()) {
            final var pageNum = e.getKey();
            final var pkList = e.getValue();
            final var entry = new AdminPageEntry(Globals.ADMIN_PAGES_PROJECT_NAME, collName, pageNum);
            entry.setEntryCount(pkList.size());
            entry.setPageSize(pkList.stream().mapToLong(PkIndexEntry::getLength).sum());
            entries.add(entry);
        }
        pages.put(pagesCollectionKey(collName), new CopyOnWriteArrayList<>(entries));
    }

    List<PkIndexEntry> pkIndexesForPagesCollection(String pagesCollName) {
        final var list = pagesPkIndexes.get(pagesCollectionKey(pagesCollName));
        return list != null ? list : List.of();
    }

    private static String pagesCollectionKey(String pagesCollName) {
        return Cache.getCollectionIdentifier(Globals.ADMIN_PAGES_PROJECT_NAME, pagesCollName);
    }

    List<AdminPageEntry> getAdminPageEntries(String projectName, String collName) {
        return pages.get(Cache.getCollectionIdentifier(projectName, collName));
    }

    AdminPageEntry getAdminPageEntry(String projectName, String collName, long page) {
        final var entries = pages.get(Cache.getCollectionIdentifier(projectName, collName));
        if (entries == null)
            return null;
        return findPage(entries, page);
    }

    void addAdminPageEntries(String projectName, String collName, AdminPageEntry adminPageEntry) {
        pageList(projectName, collName).add(adminPageEntry);
    }

    void addAdminPageEntries(String projectName, String collName, List<AdminPageEntry> adminPageEntries) {
        if (!adminPageEntries.isEmpty()) {
            pageList(projectName, collName).addAll(adminPageEntries);
        }
    }

    private List<AdminPageEntry> pageList(String projectName, String collName) {
        return pages.computeIfAbsent(Cache.getCollectionIdentifier(projectName, collName),
                _ -> new CopyOnWriteArrayList<>());
    }

    void updatePageSizeInMemory(String projectName, String collName, long page, long bytesDelta) {
        updatePageSizeInMemory(projectName, collName, page, bytesDelta, 1);
    }

    void updatePageSizeInMemory(String projectName, String collName, long page, long bytesDelta, int addedEntries) {
        underAdminPagesLock(projectName, collName, page, bytesDelta,
                () -> applyPageSizeDelta(projectName, collName, page, bytesDelta, addedEntries));
    }

    void updatePageSizeForUpdateInMemory(String projectName, String collName, long page, long bytesDelta) {
        underAdminPagesLock(projectName, collName, page, bytesDelta,
                () -> applyPageSizeDeltaKeepingCount(projectName, collName, page, bytesDelta));
    }

    private void underAdminPagesLock(String projectName, String collName, long page, long bytesDelta, Runnable apply) {
        final var pagesCollName = AdminPageEntry.pagesCollectionName(projectName, collName);
        var reinterrupt = false;
        try {
            locks.lock(Globals.ADMIN_PAGES_PROJECT_NAME, pagesCollName);
        } catch (InterruptedException e) {
            reinterrupt = true;
            if (!acquireDespiteInterruption(pagesCollName)) {
                Thread.currentThread().interrupt();
                logger.error("Dropped a page size delta of " + bytesDelta + " for " + projectName + "|" + collName
                        + " page " + page + ": nothing recomputes page sizes before a restart, so first-fit"
                        + " may overfill that page past maxPageSize");
                return;
            }
        }
        try {
            apply.run();
        } finally {
            locks.release(Globals.ADMIN_PAGES_PROJECT_NAME, pagesCollName);
            if (reinterrupt) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean acquireDespiteInterruption(String pagesCollName) {
        final var deadline = System.currentTimeMillis() + INTERRUPTED_LOCK_BUDGET_MS;
        while (System.currentTimeMillis() < deadline) {
            if (locks.tryLockWrite(Globals.ADMIN_PAGES_PROJECT_NAME, pagesCollName)) {
                return true;
            }
            Thread.onSpinWait();
        }
        return false;
    }

    private void applyPageSizeDelta(String projectName, String collName, long page, long bytesDelta, int addedEntries) {
        final var pageEntries = pageList(projectName, collName);
        final var existing = findPage(pageEntries, page);
        if (existing != null) {
            existing.setPageSize(existing.getPageSize() + bytesDelta);
            existing.setEntryCount(existing.getEntryCount() + addedEntries);
        } else if (addedEntries >= 0) {
            final var newEntry = new AdminPageEntry(projectName, collName, page);
            newEntry.setPageSize(bytesDelta);
            newEntry.setEntryCount(addedEntries);
            pageEntries.add(newEntry);
        }
    }

    private void applyPageSizeDeltaKeepingCount(String projectName, String collName, long page, long bytesDelta) {
        final var existing = findPage(pageList(projectName, collName), page);
        if (existing != null) {
            existing.setPageSize(existing.getPageSize() + bytesDelta);
        }
    }

    private static AdminPageEntry findPage(List<AdminPageEntry> entries, long page) {
        for (final var entry : entries) {
            if (entry.getPage() == page) {
                return entry;
            }
        }
        return null;
    }

    List<PkIndexEntry> getAdminPagePkIndexes(String projectName, String collName) {
        return pagesPkIndexes.computeIfAbsent(Cache.getCollectionIdentifier(projectName, collName),
                _ -> new ArrayList<>());
    }

    void removeAdminPageEntries(String projectName, String collName) {
        final var collId = Cache.getCollectionIdentifier(projectName, collName);
        pages.remove(collId);
        pagesPkIndexes.remove(collId);
    }

    long selectPageForInsert(String projectName, String collName, int entryByteSize) {
        return selectPageForInsert(pageList(projectName, collName), entryByteSize, Map.of(),
                fs.pageFileLengthsOf(projectName, collName));
    }

    long[] selectPagesForInsert(String projectName, String collName, int[] entryByteSizes) {
        final var placement = new BatchPagePlacement(pageList(projectName, collName),
                fs.pageFileLengthsOf(projectName, collName), configuration.getMaxPageSize(), entryByteSizes);
        final var targets = new long[entryByteSizes.length];
        for (var index = 0; index < entryByteSizes.length; index++) {
            targets[index] = placement.place(entryByteSizes[index]);
        }
        return targets;
    }

    private long selectPageForInsert(List<AdminPageEntry> pageEntries, int entryByteSize,
            Map<Long, Long> pendingPageBytes, LongUnaryOperator fileLengthOf) {
        final var maxPageBytes = configuration.getMaxPageSize();
        // Without the pending pages, a bulk insert into a fresh collection scatters one entry per page.
        var bestFit = -1L;
        var maxPage = -1L;
        for (final var entry : pageEntries) {
            final var page = entry.getPage();
            if (page > maxPage) {
                maxPage = page;
            }
            if (bestFit >= 0 && page >= bestFit) {
                continue;
            }
            final var pending = pendingPageBytes.getOrDefault(page, 0L);
            final var liveBytes = entry.getPageSize() + pending;
            if (!PagePlacement.liveBytesAdmit(liveBytes, entryByteSize, maxPageBytes)) {
                continue;
            }
            if (PagePlacement.acceptsInsert(liveBytes, fileLengthOf.applyAsLong(page) + pending, entryByteSize,
                    maxPageBytes)) {
                bestFit = page;
            }
        }
        for (final var pending : pendingPageBytes.entrySet()) {
            final var page = pending.getKey();
            if (page > maxPage) {
                maxPage = page;
            }
            if (findPage(pageEntries, page) != null) {
                continue;
            }
            if (pending.getValue() + entryByteSize <= maxPageBytes && (bestFit < 0 || page < bestFit)) {
                bestFit = page;
            }
        }
        return bestFit >= 0 ? bestFit : maxPage + 1L;
    }
}
