package org.ccg.mpp.ops.admin;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.ccg.mpp.bckg_ops.PendingIndexWrites;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.concurrency.DeferredApplyHook;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.PkIndexEntry;
import org.ccg.mpp.fs.DeferredApplies;
import org.ccg.mpp.fs.DeferredApplyListener;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.log.Logger;

public final class DeferredWrites implements DeferredApplyHook, DeferredApplyListener {
    private static final Logger logger = Logger.logFor(DeferredWrites.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);
    private static final Set<String> staleRows = ConcurrentHashMap.newKeySet();

    DeferredWrites() {
    }

    public static void install() {
        final var installed = new DeferredWrites();
        fs.onDeferredApply(installed);
        ResourceLocking.useDeferredApplyHook(installed);
    }

    public static void reconcileRowsOnNextUse(String projectName, String collName) {
        staleRows.add(Cache.getCollectionIdentifier(projectName, collName));
    }

    public static void forgetCollection(String projectName, String collName) {
        staleRows.remove(Cache.getCollectionIdentifier(projectName, collName));
    }

    public static void forgetProject(String projectName) {
        final var prefix = projectName + Globals.COLL_IDENTIFIER_SEPARATOR;
        staleRows.removeIf(lockName -> lockName.startsWith(prefix));
    }

    @Override
    public boolean pending(String lockName) {
        return DeferredApplies.pending(lockName) || !staleRows.isEmpty() && staleRows.contains(lockName);
    }

    @Override
    public void apply(String lockName) {
        final var separator = lockName.indexOf(Globals.COLL_IDENTIFIER_SEPARATOR);
        if (separator <= 0 || lockName.indexOf(Globals.COLL_IDENTIFIER_SEPARATOR, separator + 1) >= 0) {
            return;
        }
        final var projectName = lockName.substring(0, separator);
        final var collName = lockName.substring(separator + 1);
        final boolean applied;
        try {
            applied = fs.applyDeferredWrites(projectName, collName);
        } catch (IOException e) {
            logger.warning("Could not apply the logged writes of " + lockName + " before using the collection; the"
                    + " next use retries: " + e.getMessage());
            return;
        }
        final var rowsWereStale = staleRows.remove(lockName);
        if ((applied || rowsWereStale) && !Globals.ADMIN_PAGES_PROJECT_NAME.equals(projectName)) {
            reconcilePageRows(projectName, collName);
        }
    }

    private static void reconcilePageRows(String projectName, String collName) {
        try {
            PageOccupancyReconciler.reconcileRows(projectName, collName);
        } catch (InterruptedException e) {
            reconcileRowsOnNextUse(projectName, collName);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            reconcileRowsOnNextUse(projectName, collName);
            logger.warning(
                    "Could not reconcile the page rows of " + Cache.getCollectionIdentifier(projectName, collName)
                            + "; its next use retries: " + e.getMessage());
        }
    }

    @Override
    public long replaying(String projectName, String collName, List<PkIndexEntry> rows) {
        return isUserProject(projectName) ? pendingIndexWrites.mark(projectName, collName, idsOf(rows)) : 0L;
    }

    @Override
    public void replayed(String projectName, String collName, List<PkIndexEntry> rows, long generation) {
        if (!isUserProject(projectName)) {
            AdminReplayedRows.publish(projectName, collName, rows);
            return;
        }
        reconcileRowsOnNextUse(projectName, collName);
        repairAfterRedo(projectName, collName, rows, generation);
    }

    @Override
    public void notReplayed(String projectName, String collName, List<PkIndexEntry> rows, long generation) {
        if (isUserProject(projectName)) {
            repairAfterRedo(projectName, collName, rows, generation);
        }
    }

    private static void repairAfterRedo(String projectName, String collName, List<PkIndexEntry> rows, long generation) {
        cache.evictCollection(projectName, collName);
        PageOccupancyReconciler.scheduleIndexRepairFor(
                List.of(new PageOccupancyReconciler.ReplayedCollection(projectName, collName, rows, generation)));
    }

    private static boolean isUserProject(String projectName) {
        return !Globals.ADMIN_PROJECT_NAME.equals(projectName) && !Globals.ADMIN_PAGES_PROJECT_NAME.equals(projectName);
    }

    private static List<String> idsOf(List<PkIndexEntry> rows) {
        return rows.stream().map(PkIndexEntry::getValue).toList();
    }
}
