package org.ccg.mpp.ops.admin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.ccg.mpp.bckg_ops.events.EntityRef;
import org.ccg.mpp.bckg_ops.events.EventType;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.cache.CollectionIdentifiers;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.DbEntry;
import org.ccg.mpp.data.IndexedDbEntry;
import org.ccg.mpp.data.PkIndexEntry;
import org.ccg.mpp.data.admin.AdminPageEntry;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.log.Logger;

public final class AdminPageHelper {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final Logger logger = Logger.logFor(AdminPageHelper.class);
    private static final Configuration configuration = Configuration.getInstance();
    private static final long UNSTAMPED_INCARNATION = 0L;

    private AdminPageHelper() {
    }

    private record PageDelta(AdminPageEntry live, AdminPageEntry pending) {
        void publish() {
            live.setEntryCount(pending.getEntryCount());
            live.setPageSize(pending.getPageSize());
        }
    }

    private static AdminPageEntry pendingCopy(String projectName, String collName, AdminPageEntry live, int deltaCount,
            long deltaBytes) {
        final var pending = new AdminPageEntry(projectName, collName, live.getPage());
        pending.setEntryCount(live.getEntryCount() + deltaCount);
        pending.setPageSize(live.getPageSize() + deltaBytes);
        return pending;
    }

    private static void lockAdminPageCollection(String projectName, String collName) throws InterruptedException {
        locks.lock(Globals.ADMIN_PAGES_PROJECT_NAME, AdminPageEntry.pagesCollectionName(projectName, collName));
    }

    private static void releaseAdminPageCollection(String projectName, String collName) {
        locks.release(Globals.ADMIN_PAGES_PROJECT_NAME, AdminPageEntry.pagesCollectionName(projectName, collName));
    }

    @SuppressWarnings("RedundantThrows")
    public static void bulkUpdateEntryCount(String projectName, String collName, EventType type,
            List<EntityRef> inserted, long incarnation) throws IOException, InterruptedException {
        bookWritePathPages(projectName, collName, type, inserted, incarnation);
    }

    @SuppressWarnings("RedundantThrows")
    public static void updateEntryCount(String projectName, String collName, EventType type, EntityRef entity,
            long incarnation) throws IOException, InterruptedException {
        bookWritePathPages(projectName, collName, type, List.of(entity), incarnation);
    }

    private static void bookWritePathPages(String projectName, String collName, EventType type, List<EntityRef> refs,
            long incarnation) throws InterruptedException {
        if (type == EventType.CREATED) {
            bookWritePathPages(projectName, collName, refs, List.of(), incarnation);
        } else {
            bookWritePathPages(projectName, collName, List.of(), refs, incarnation);
        }
    }

    public static void bookWritePathPages(String projectName, String collName, List<EntityRef> created,
            List<EntityRef> others, long incarnation) throws InterruptedException {
        if (created.isEmpty() && others.isEmpty()) {
            return;
        }
        lockAdminPageCollection(projectName, collName);
        try {
            if (Globals.ADMIN_PROJECT_NAME.equals(projectName)
                    || CollectionIncarnation.isCurrent(projectName, collName, incarnation)) {
                WritePathPageBooking.book(projectName, collName, created, others, incarnation);
            }
        } finally {
            releaseAdminPageCollection(projectName, collName);
        }
    }

    public static void recordLandedDelta(String collName, EventType type, List<DbEntry> entries) {
        recordLanded(collName, () -> baseUpdateEntryCount(Globals.ADMIN_PROJECT_NAME, collName, type, entries, false));
    }

    public static void recordLandedRefs(String collName, EventType type, List<EntityRef> entries) {
        recordLanded(collName, () -> baseUpdateEntryCountOfRefs(Globals.ADMIN_PROJECT_NAME, collName, type, entries));
    }

    private interface PageRowBooking {
        void book() throws InterruptedException, IOException;
    }

    private static void recordLanded(String collName, PageRowBooking booking) {
        try {
            booking.book();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted recording the page row of a landed admin/" + collName + " write", e);
        } catch (IOException e) {
            logger.error("Failed to record the page row of a landed admin/" + collName + " write", e);
        }
    }

    @SuppressWarnings("RedundantThrows")
    public static void baseUpdateEntryCount(final String projectName, final String collName, final EventType type,
            final List<DbEntry> insertedOrDeleted, final boolean skipMemoryDeltaForCreated)
            throws InterruptedException, IOException {
        bookRefs(projectName, collName, type, EntityRef.allOf(insertedOrDeleted),
                type == EventType.CREATED && skipMemoryDeltaForCreated);
    }

    @SuppressWarnings("RedundantThrows")
    public static void baseUpdateEntryCountOfRefs(final String projectName, final String collName, final EventType type,
            final List<EntityRef> insertedOrDeleted) throws InterruptedException, IOException {
        bookRefs(projectName, collName, type, insertedOrDeleted, false);
    }

    private static void bookRefs(final String projectName, final String collName, final EventType type,
            final List<EntityRef> insertedOrDeleted, final boolean deltaAlreadyAppliedOnWritePath)
            throws InterruptedException {
        if (insertedOrDeleted.isEmpty()) {
            return;
        }
        lockAdminPageCollection(projectName, collName);
        try {
            // Re-check under the lock: a concurrent drop must not lead to orphan page metadata.
            if (!Globals.ADMIN_PROJECT_NAME.equals(projectName)
                    && !CollectionIncarnation.isCurrent(projectName, collName, UNSTAMPED_INCARNATION)) {
                return;
            }
            final var grouped = insertedOrDeleted.stream().collect(Collectors.groupingBy(EntityRef::page));
            final var deltas = new ArrayList<PageDelta>();
            final var newPages = new ArrayList<AdminPageEntry>();
            for (var groupedEntry : grouped.entrySet()) {
                final var page = groupedEntry.getKey();
                final var groupEntries = groupedEntry.getValue();
                final var sumBytes = groupEntries.stream().mapToLong(EntityRef::byteSize).sum();
                final var deltaBytes = switch (type) {
                    case UPDATED -> sumBytes - groupEntries.stream().mapToLong(EntityRef::previousByteSize).sum();
                    case CREATED -> sumBytes;
                    case DELETED -> -sumBytes;
                };
                final var deltaCount = switch (type) {
                    case UPDATED -> 0;
                    case CREATED -> groupEntries.size();
                    case DELETED -> -groupEntries.size();
                };
                final var live = cache.getAdminPageEntry(projectName, collName, page);
                if (live != null) {
                    final var pending = deltaAlreadyAppliedOnWritePath
                            ? pendingCopy(projectName, collName, live, 0, 0L)
                            : pendingCopy(projectName, collName, live, deltaCount, deltaBytes);
                    deltas.add(new PageDelta(live, pending));
                } else if (type == EventType.CREATED) {
                    final var newEntry = new AdminPageEntry(projectName, collName, page);
                    newEntry.setEntryCount(groupEntries.size());
                    newEntry.setPageSize(sumBytes);
                    newPages.add(newEntry);
                }
            }
            cache.addAdminPageEntries(projectName, collName, newPages);
            deltas.forEach(PageDelta::publish);
            PageRowWriteBack.touched(projectName, collName, UNSTAMPED_INCARNATION, touchedPages(newPages, deltas),
                    System.currentTimeMillis());
        } finally {
            releaseAdminPageCollection(projectName, collName);
        }
    }

    private static List<Long> touchedPages(List<AdminPageEntry> newPages, List<PageDelta> deltas) {
        final var pages = new ArrayList<Long>(newPages.size() + deltas.size());
        newPages.forEach(entry -> pages.add(entry.getPage()));
        deltas.forEach(delta -> pages.add(delta.live().getPage()));
        return pages;
    }

    static void writeBackRows(String projectName, String collName, long incarnation, Set<Long> pages)
            throws IOException, InterruptedException {
        lockAdminPageCollection(projectName, collName);
        try {
            if (!Globals.ADMIN_PROJECT_NAME.equals(projectName)
                    && !CollectionIncarnation.isCurrent(projectName, collName, incarnation)) {
                return;
            }
            final var pagesPerCollectionName = AdminPageEntry.pagesCollectionName(projectName, collName);
            final var live = cache.getAdminPageEntries(projectName, collName);
            if (live == null) {
                return;
            }
            final var touched = live.stream().filter(entry -> pages.contains(entry.getPage()))
                    .map(entry -> pendingCopy(projectName, collName, entry, 0, 0L)).toList();
            if (!touched.isEmpty()) {
                fs.createCollectionFile(Globals.ADMIN_PAGES_PROJECT_NAME, pagesPerCollectionName);
                updateTouchedPagesInFileSystem(pagesPerCollectionName, touched);
            }
        } finally {
            releaseAdminPageCollection(projectName, collName);
        }
    }

    private static void insertAdminPages(String pagesPerCollectionName, List<AdminPageEntry> newPages)
            throws IOException {
        final var sizes = new int[newPages.size()];
        for (var index = 0; index < sizes.length; index++) {
            sizes[index] = newPages.get(index).byteSize();
        }
        final var targets = cache.selectPagesForInsert(Globals.ADMIN_PAGES_PROJECT_NAME, pagesPerCollectionName, sizes);
        for (var index = 0; index < sizes.length; index++) {
            newPages.get(index).setStoragePage(targets[index]);
        }
        final var inserted = fs.bulkInsertIntoCollection(Globals.ADMIN_PAGES_PROJECT_NAME, pagesPerCollectionName,
                newPages, AdminPageEntry::getStoragePage);
        final var pkIdxList = cache.getAdminPagePkIndexes(Globals.ADMIN_PAGES_PROJECT_NAME, pagesPerCollectionName);
        for (var ie : inserted) {
            pkIdxList.add(ie.getIndex());
        }
        trackInMemoryAdminPages(pagesPerCollectionName, inserted);
    }

    private static void trackInMemoryAdminPages(String pagesPerCollectionName, List<IndexedDbEntry> inserted) {
        for (var ie : inserted) {
            final var page = ie.getIndex().getPage();
            final var bytes = ie.getIndex().getLength();
            final var existing = cache.getAdminPageEntry(Globals.ADMIN_PAGES_PROJECT_NAME, pagesPerCollectionName,
                    page);
            if (existing != null) {
                existing.setEntryCount(existing.getEntryCount() + 1);
                existing.setPageSize(existing.getPageSize() + bytes);
            } else {
                final var newEntry = new AdminPageEntry(Globals.ADMIN_PAGES_PROJECT_NAME, pagesPerCollectionName, page);
                newEntry.setEntryCount(1);
                newEntry.setPageSize(bytes);
                cache.addAdminPageEntries(Globals.ADMIN_PAGES_PROJECT_NAME, pagesPerCollectionName, newEntry);
            }
        }
    }

    private static void trackInMemoryAdminPagesForUpdate(String pagesPerCollectionName, List<IndexedDbEntry> updated) {
        for (var ie : updated) {
            final var page = ie.getIndex().getPage();
            final var newBytes = ie.getIndex().getLength();
            final var prevBytes = ie.getPreviousByteSize();
            final var existing = cache.getAdminPageEntry(Globals.ADMIN_PAGES_PROJECT_NAME, pagesPerCollectionName,
                    page);
            if (existing != null) {
                existing.setPageSize(existing.getPageSize() + newBytes - prevBytes);
            } else {
                final var newEntry = new AdminPageEntry(Globals.ADMIN_PAGES_PROJECT_NAME, pagesPerCollectionName, page);
                newEntry.setEntryCount(0);
                newEntry.setPageSize(newBytes);
                cache.addAdminPageEntries(Globals.ADMIN_PAGES_PROJECT_NAME, pagesPerCollectionName, newEntry);
            }
        }
    }

    private static void updateTouchedPagesInFileSystem(String pagesPerCollectionName, List<AdminPageEntry> touchedPages)
            throws IOException {
        final var pkIdxList = cache.getAdminPagePkIndexes(Globals.ADMIN_PAGES_PROJECT_NAME, pagesPerCollectionName);
        final var indexedEntriesToUpdate = new ArrayList<IndexedDbEntry>();
        final var firstTouchPages = new ArrayList<AdminPageEntry>();
        for (var touchedPage : touchedPages) {
            final var matchingPkIdx = pkIdxList.stream().filter(pk -> pk.getValue().equals(touchedPage.get_id()))
                    .findFirst().orElse(null);
            if (matchingPkIdx == null) {
                firstTouchPages.add(touchedPage);
                continue;
            }
            final var indexedEntry = new IndexedDbEntry();
            indexedEntry.set_id(touchedPage.get_id());
            indexedEntry.setProjectName(Globals.ADMIN_PAGES_PROJECT_NAME);
            indexedEntry.setCollectionName(pagesPerCollectionName);
            indexedEntry.setData(touchedPage.getData());
            indexedEntry.setIndex(matchingPkIdx);
            indexedEntriesToUpdate.add(indexedEntry);
        }
        if (!indexedEntriesToUpdate.isEmpty()) {
            final var updated = fs.bulkUpdateFromCollection(Globals.ADMIN_PAGES_PROJECT_NAME, pagesPerCollectionName,
                    indexedEntriesToUpdate, pkIdxList, null);
            applyBulkUpdateResult(pagesPerCollectionName, pkIdxList, updated);
        }
        if (!firstTouchPages.isEmpty()) {
            insertAdminPages(pagesPerCollectionName, firstTouchPages);
        }
    }

    private static void applyBulkUpdateResult(String pagesPerCollectionName, List<PkIndexEntry> pkIdxList,
            List<IndexedDbEntry> updated) {
        for (var ie : updated) {
            pkIdxList.removeIf(pk -> pk.getValue().equals(ie.get_id()));
            pkIdxList.add(ie.getIndex());
        }
        trackInMemoryAdminPagesForUpdate(pagesPerCollectionName, updated);
    }

    public static void replacePageEntries(String projectName, String collName, List<AdminPageEntry> corrected)
            throws IOException, InterruptedException {
        lockAdminPageCollection(projectName, collName);
        try {
            final var pagesPerCollectionName = AdminPageEntry.pagesCollectionName(projectName, collName);
            fs.createCollectionFile(Globals.ADMIN_PAGES_PROJECT_NAME, pagesPerCollectionName);
            final var existing = cache.getAdminPageEntries(projectName, collName);
            final var deltas = new ArrayList<PageDelta>();
            final var newPages = new ArrayList<AdminPageEntry>();
            for (final var target : corrected) {
                final var live = existing == null
                        ? null
                        : existing.stream().filter(p -> p.getPage() == target.getPage()).findFirst().orElse(null);
                if (live == null) {
                    newPages.add(target);
                } else {
                    deltas.add(new PageDelta(live, pendingCopy(projectName, collName, live,
                            target.getEntryCount() - live.getEntryCount(), target.getPageSize() - live.getPageSize())));
                }
            }
            if (!newPages.isEmpty()) {
                insertAdminPages(pagesPerCollectionName, newPages);
            }
            if (!deltas.isEmpty()) {
                updateTouchedPagesInFileSystem(pagesPerCollectionName,
                        deltas.stream().map(PageDelta::pending).toList());
            }
            cache.addAdminPageEntries(projectName, collName, newPages);
            deltas.forEach(PageDelta::publish);
        } finally {
            releaseAdminPageCollection(projectName, collName);
        }
    }

    public static void createPageCollections(String projectName, String collName) throws IOException {
        final var pagesCollName = AdminPageEntry.pagesCollectionName(projectName, collName);
        deletePageCollections(projectName, collName);
        fs.createCollectionFile(Globals.ADMIN_PAGES_PROJECT_NAME, pagesCollName);
    }

    public static void deletePageCollections(String projectName, String collName) {
        final var pagesCollName = AdminPageEntry.pagesCollectionName(projectName, collName);
        PageRowWriteBack.forget(projectName, collName);
        final var exclusive = lockAdminPagesForDrop(projectName, collName);
        try {
            if (sharesPageFolder(projectName, collName)) {
                deleteOwnRows(projectName, collName, pagesCollName);
            } else {
                fs.deleteCollectionFiles(Globals.ADMIN_PAGES_PROJECT_NAME, pagesCollName);
                cache.removeAdminPageEntries(Globals.ADMIN_PAGES_PROJECT_NAME, pagesCollName);
            }
            cache.removeAdminPageEntries(projectName, collName);
        } finally {
            if (exclusive) {
                releaseAdminPageCollection(projectName, collName);
            }
            locks.removeLock(Globals.ADMIN_PAGES_PROJECT_NAME, pagesCollName);
            CollectionIdentifiers.forget(Globals.ADMIN_PAGES_PROJECT_NAME, pagesCollName);
        }
    }

    private static boolean sharesPageFolder(String projectName, String collName) {
        final var pagesCollName = AdminPageEntry.pagesCollectionName(projectName, collName);
        for (final var dbEntry : cache.getAllAdminProjectEntries()) {
            final var collections = dbEntry.getCollections();
            for (final var otherColl : collections == null ? List.<String>of() : collections) {
                final var isSelf = dbEntry.get_id().equals(projectName) && otherColl.equals(collName);
                if (!isSelf && AdminPageEntry.pagesCollectionName(dbEntry.get_id(), otherColl).equals(pagesCollName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void deleteOwnRows(String projectName, String collName, String pagesCollName) {
        final var ownPrefix = Cache.getCollectionIdentifier(projectName, collName) + Globals.COLL_IDENTIFIER_SEPARATOR;
        final var pkIdxList = cache.getAdminPagePkIndexes(Globals.ADMIN_PAGES_PROJECT_NAME, pagesCollName);
        final var ownRows = pkIdxList.stream().filter(pk -> pk.getValue().startsWith(ownPrefix)).toList();
        for (final var row : ownRows) {
            try {
                fs.deleteFromCollection(row);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            pkIdxList.remove(row);
            trackInMemoryAdminPageRemoval(pagesCollName, row);
        }
    }

    private static void trackInMemoryAdminPageRemoval(String pagesPerCollectionName, PkIndexEntry removed) {
        final var existing = cache.getAdminPageEntry(Globals.ADMIN_PAGES_PROJECT_NAME, pagesPerCollectionName,
                removed.getPage());
        if (existing != null) {
            existing.setEntryCount(existing.getEntryCount() - 1);
            existing.setPageSize(existing.getPageSize() - removed.getLength());
        }
    }

    private static boolean lockAdminPagesForDrop(String projectName, String collName) {
        try {
            final var acquired = locks.tryLockWrite(Globals.ADMIN_PAGES_PROJECT_NAME,
                    AdminPageEntry.pagesCollectionName(projectName, collName),
                    configuration.getTransactionLockTimeoutMs());
            if (!acquired) {
                logger.warning("Dropping the page metadata of " + projectName + "|" + collName + " without its"
                        + " admin_pages lock: the background page writer may re-create rows for a collection"
                        + " that no longer exists");
            }
            return acquired;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
