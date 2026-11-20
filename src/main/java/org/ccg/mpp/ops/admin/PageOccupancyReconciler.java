package org.ccg.mpp.ops.admin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.ccg.mpp.bckg_ops.BackgroundTaskManager;
import org.ccg.mpp.bckg_ops.PendingIndexWrites;
import org.ccg.mpp.bckg_ops.events.IndexRepairEvent;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.DbEntry;
import org.ccg.mpp.data.PkIndexEntry;
import org.ccg.mpp.data.admin.AdminPageEntry;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.log.Logger;

public final class PageOccupancyReconciler {
    private static final Cache cache = IocContainer.get(Cache.class);
    @SuppressWarnings("FieldMayBeFinal")
    private static FileSystem fs = IocContainer.get(FileSystem.class);
    private static final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);
    @SuppressWarnings("FieldMayBeFinal")
    private static BackgroundTaskManager taskManager = IocContainer.get(BackgroundTaskManager.class);
    private static final Logger logger = Logger.logFor(PageOccupancyReconciler.class);
    private static final int ENTRY_COUNT = 0;
    private static final int LIVE_BYTES = 1;
    private static final long[] NOTHING_LIVE = new long[2];

    public record ReplayedCollection(String projectName, String collName, List<PkIndexEntry> rows, long generation) {
    }

    private PageOccupancyReconciler() {
    }

    public static void reconcileAll() {
        healAdminPageTails();
        reconcileAdminRows();
        for (final var dbEntry : List.copyOf(cache.getAllAdminProjectEntries())) {
            for (final var collName : cache.getCollectionNamesForProject(dbEntry.get_id())) {
                reconcileQuietly(dbEntry.get_id(), collName);
            }
        }
    }

    public static void scheduleIndexCleanupFor(List<PkIndexEntry> completedDeletes) {
        final var byCollection = new LinkedHashMap<String, List<PkIndexEntry>>();
        for (final var deleted : completedDeletes) {
            final var projectName = deleted.getProjectName();
            final var collName = deleted.getCollectionName();
            if (isAdminProject(projectName) || cache.getAdminCollectionEntry(projectName, collName) == null) {
                continue;
            }
            byCollection.computeIfAbsent(Cache.getCollectionIdentifier(projectName, collName), _ -> new ArrayList<>())
                    .add(deleted);
        }
        for (final var rows : byCollection.values()) {
            final var first = rows.getFirst();
            scheduleIndexRepairOf(first.getProjectName(), first.getCollectionName(), idsOf(rows));
        }
    }

    public static List<ReplayedCollection> markReplayed(List<PkIndexEntry> written) {
        final var byCollection = new LinkedHashMap<String, List<PkIndexEntry>>();
        for (final var row : written) {
            if (!isAdminProject(row.getProjectName())) {
                byCollection
                        .computeIfAbsent(Cache.getCollectionIdentifier(row.getProjectName(), row.getCollectionName()),
                                _ -> new ArrayList<>())
                        .add(row);
            }
        }
        final var replayed = new ArrayList<ReplayedCollection>(byCollection.size());
        for (final var rows : byCollection.values()) {
            final var projectName = rows.getFirst().getProjectName();
            final var collName = rows.getFirst().getCollectionName();
            replayed.add(new ReplayedCollection(projectName, collName, rows,
                    pendingIndexWrites.mark(projectName, collName, idsOf(rows))));
        }
        return replayed;
    }

    public static void scheduleIndexRepairFor(List<ReplayedCollection> replayed) {
        for (final var collection : replayed) {
            final var projectName = collection.projectName();
            final var collName = collection.collName();
            final var ids = idsOf(collection.rows());
            if (cache.getAdminCollectionEntry(projectName, collName) == null) {
                pendingIndexWrites.clear(projectName, collName, ids, collection.generation());
                continue;
            }
            submitRepair(projectName, collName, ids, collection.generation());
        }
    }

    public static void scheduleIndexRepairOf(String projectName, String collName, List<String> ids) {
        if (!ids.isEmpty()) {
            submitRepair(projectName, collName, ids, pendingIndexWrites.markRecovered(projectName, collName, ids));
        }
    }

    private static void submitRepair(String projectName, String collName, List<String> ids, long generation) {
        taskManager.submitMaintenance(new IndexRepairEvent(projectName, collName, ids,
                CollectionIncarnation.current(projectName, collName), generation));
    }

    private static List<String> idsOf(List<PkIndexEntry> rows) {
        return rows.stream().map(PkIndexEntry::getValue).toList();
    }

    private static boolean isAdminProject(String projectName) {
        return Globals.ADMIN_PROJECT_NAME.equals(projectName) || Globals.ADMIN_PAGES_PROJECT_NAME.equals(projectName);
    }

    private static void healAdminPageTails() {
        for (final var collName : Globals.ADMIN_COLLECTION_NAMES) {
            healQuietly(Globals.ADMIN_PROJECT_NAME, collName);
            healQuietly(Globals.ADMIN_PAGES_PROJECT_NAME, pageRowCollectionOf(Globals.ADMIN_PROJECT_NAME, collName));
        }
    }

    private static void reconcileAdminRows() {
        for (final var collName : Globals.ADMIN_COLLECTION_NAMES) {
            try {
                if (reconcileAdminRows(collName)) {
                    logger.warning("Rebuilt the page-occupancy metadata of " + Globals.ADMIN_PROJECT_NAME + "|"
                            + collName
                            + " from its page files: they are written back, so an unclean stop leaves them behind");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                logger.warning("Could not reconcile the page-occupancy metadata of " + Globals.ADMIN_PROJECT_NAME + "|"
                        + collName + ": " + e.getMessage());
            }
        }
    }

    public static boolean reconcileAdminRows(String collName) throws Exception {
        return reconcileRows(Globals.ADMIN_PROJECT_NAME, collName);
    }

    public static boolean reconcileRows(String projectName, String collName) throws Exception {
        final var pages = fs.pageFileLengths(projectName, collName).keySet();
        final var occupancy = occupancyByPage(fs.readWholePkIndexFile(projectName, collName));
        final var rows = rowsByPage(cache.getAdminPageEntries(projectName, collName));
        if (agreesWithLiveBytes(rows, pages, occupancy) && countsAgree(rows, occupancy)) {
            return false;
        }
        replaceRows(projectName, collName, pages, occupancy, rows);
        return true;
    }

    private static boolean countsAgree(Map<Long, AdminPageEntry> rows, Map<Long, long[]> occupancy) {
        for (final var live : occupancy.entrySet()) {
            final var row = rows.get(live.getKey());
            if ((row == null ? 0L : row.getEntryCount()) != live.getValue()[ENTRY_COUNT]) {
                return false;
            }
        }
        for (final var row : rows.values()) {
            if (row.getEntryCount() != occupancy.getOrDefault(row.getPage(), NOTHING_LIVE)[ENTRY_COUNT]) {
                return false;
            }
        }
        return true;
    }

    private static String pageRowCollectionOf(String projectName, String collName) {
        return AdminPageEntry.pagesCollectionName(projectName, collName);
    }

    private static void healQuietly(String projectName, String collName) {
        try {
            fs.healTornPageTails(projectName, collName);
        } catch (Exception e) {
            logger.warning("Could not heal the page tails of " + projectName + "|" + collName + ": " + e.getMessage());
        }
    }

    private static void reconcileQuietly(String projectName, String collName) {
        try {
            if (reconcile(projectName, collName)) {
                logger.warning("Rebuilt the page-occupancy metadata of " + projectName + "|" + collName
                        + " from its page files: it no longer matched them, most likely after an unclean stop");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.warning("Could not reconcile the page-occupancy metadata of " + projectName + "|" + collName + ": "
                    + e.getMessage());
        }
    }

    public static boolean reconcile(String projectName, String collName) throws Exception {
        fs.healTornPageTails(Globals.ADMIN_PAGES_PROJECT_NAME, pageRowCollectionOf(projectName, collName));
        final var session = fs.pkRowsSession(projectName, collName);
        fs.healTornPageTails(session);
        final var retired = fs.retireDanglingPkEntries(session);
        if (!retired.isEmpty()) {
            fs.healTornPageTails(session);
            scheduleIndexCleanupFor(retired);
        }
        final var adopted = fs.adoptOrphanedRecords(session);
        if (!adopted.isEmpty()) {
            scheduleIndexRepairOf(projectName, collName, adopted.stream().map(DbEntry::get_id).toList());
        }
        final var pages = fs.pageFileLengths(projectName, collName).keySet();
        final var occupancy = occupancyByPage(session.rows());
        final var rows = rowsByPage(cache.getAdminPageEntries(projectName, collName));
        if (adopted.isEmpty() && retired.isEmpty() && agreesWithLiveBytes(rows, pages, occupancy)
                && countsAgree(rows, occupancy)) {
            return false;
        }
        replaceRows(projectName, collName, pages, occupancy, rows);
        return true;
    }

    public static void rebuildRows(String projectName, String collName) throws Exception {
        replaceRows(projectName, collName, fs.pageFileLengths(projectName, collName).keySet(),
                occupancyByPage(fs.readWholePkIndexFile(projectName, collName)),
                rowsByPage(cache.getAdminPageEntries(projectName, collName)));
    }

    private static void replaceRows(String projectName, String collName, Set<Long> pages, Map<Long, long[]> occupancy,
            Map<Long, AdminPageEntry> rows) throws Exception {
        final var corrected = new ArrayList<AdminPageEntry>();
        for (final var page : pages) {
            final var live = occupancy.getOrDefault(page, NOTHING_LIVE);
            corrected.add(row(projectName, collName, page, (int) live[ENTRY_COUNT], live[LIVE_BYTES]));
        }
        for (final var page : rows.keySet()) {
            if (!pages.contains(page)) {
                corrected.add(row(projectName, collName, page, 0, 0L));
            }
        }
        AdminPageHelper.replacePageEntries(projectName, collName, corrected);
    }

    private static Map<Long, AdminPageEntry> rowsByPage(List<AdminPageEntry> rows) {
        final var byPage = new HashMap<Long, AdminPageEntry>();
        if (rows != null) {
            for (final var row : rows) {
                byPage.put(row.getPage(), row);
            }
        }
        return byPage;
    }

    private static boolean agreesWithLiveBytes(Map<Long, AdminPageEntry> rows, Set<Long> pages,
            Map<Long, long[]> occupancy) {
        for (final var page : pages) {
            final var row = rows.get(page);
            if (row == null && !rows.isEmpty()) {
                return false;
            }
            final var recordedSize = row == null ? 0L : row.getPageSize();
            if (recordedSize != occupancy.getOrDefault(page, NOTHING_LIVE)[LIVE_BYTES]) {
                return false;
            }
        }
        for (final var row : rows.values()) {
            if (!pages.contains(row.getPage()) && (row.getPageSize() != 0 || row.getEntryCount() != 0)) {
                return false;
            }
        }
        return true;
    }

    private static Map<Long, long[]> occupancyByPage(List<PkIndexEntry> pkRows) {
        final var occupancy = new HashMap<Long, long[]>();
        for (final var pkEntry : pkRows) {
            final var live = occupancy.computeIfAbsent(pkEntry.getPage(), _ -> new long[2]);
            live[ENTRY_COUNT]++;
            live[LIVE_BYTES] += pkEntry.getLength();
        }
        return occupancy;
    }

    private static AdminPageEntry row(String projectName, String collName, long page, int entryCount, long pageSize) {
        final var entry = new AdminPageEntry(projectName, collName, page);
        entry.setEntryCount(entryCount);
        entry.setPageSize(pageSize);
        return entry;
    }
}
