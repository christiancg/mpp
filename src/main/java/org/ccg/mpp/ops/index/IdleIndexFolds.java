package org.ccg.mpp.ops.index;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.fs.PendingFold;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.log.Logger;

public final class IdleIndexFolds {
    @SuppressWarnings("FieldMayBeFinal")
    private static Logger logger = Logger.logFor(IdleIndexFolds.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ResourceLocking rl = IocContainer.get(ResourceLocking.class);
    private static final Map<String, DueFold> due = new ConcurrentHashMap<>();
    private static final long MAX_RETRY_WAIT_MS = 60_000L;
    @SuppressWarnings("FieldMayBeFinal")
    private static FileSystem fs = IocContainer.get(FileSystem.class);

    record DueFold(String projectName, String collName, String fieldName, String typeLabel, String identifier,
            long markedAtMillis, int failures) {
        boolean quietAt(long nowMillis) {
            final var wait = Math.min(Globals.IDLE_INDEX_FOLD_QUIET_MS << Math.min(failures, Long.SIZE - 2),
                    MAX_RETRY_WAIT_MS);
            return nowMillis - markedAtMillis >= wait;
        }

        DueFold failedAt(long nowMillis) {
            return new DueFold(projectName, collName, fieldName, typeLabel, identifier, nowMillis, failures + 1);
        }
    }

    private IdleIndexFolds() {
    }

    static void markDue(String projectName, String collName, String fieldName, String typeLabel, String identifier) {
        due.put(keyOf(projectName, collName, identifier),
                new DueFold(projectName, collName, fieldName, typeLabel, identifier, System.currentTimeMillis(), 0));
    }

    public static int runDue(long nowMillis) {
        if (due.isEmpty()) {
            return 0;
        }
        var ran = 0;
        for (final var fold : List.copyOf(due.values())) {
            if (ran == Globals.IDLE_INDEX_FOLDS_PER_PASS) {
                break;
            }
            if (!fold.quietAt(nowMillis) || !rl.tryLockIndex(fold.projectName(), fold.collName(), fold.fieldName())) {
                continue;
            }
            ran++;
            try {
                foldHoldingIndexLock(fold, nowMillis);
            } finally {
                rl.releaseIndex(fold.projectName(), fold.collName(), fold.fieldName());
            }
        }
        return ran;
    }

    public static void forgetCollection(String projectName, String collName) {
        due.values().removeIf(fold -> fold.projectName().equals(projectName) && fold.collName().equals(collName));
    }

    public static void forgetProject(String projectName) {
        due.values().removeIf(fold -> fold.projectName().equals(projectName));
    }

    private static void foldHoldingIndexLock(DueFold fold, long nowMillis) {
        final var key = keyOf(fold.projectName(), fold.collName(), fold.identifier());
        final var resident = cache.residentFieldIndex(fold.projectName(), fold.collName(), fold.identifier());
        if (resident == null) {
            due.remove(key, fold);
            return;
        }
        try {
            final var pending = fs.appendIndexLog(fold.projectName(), fold.collName(), fold.fieldName(),
                    fold.typeLabel(), List.of(), resident, false);
            if (pending == null) {
                due.remove(key, fold);
                return;
            }
            final var outcome = fs.fold(pending);
            if (outcome == PendingFold.Outcome.SKIPPED) {
                return;
            }
            FieldIndexFolds.settle(new DeferredFold(fold.identifier(), pending), outcome);
            due.remove(key, fold);
        } catch (IOException e) {
            due.replace(key, fold, fold.failedAt(nowMillis));
            logger.warning("Could not fold the index log of "
                    + Cache.getCollectionIdentifier(fold.projectName(), fold.collName()) + "." + fold.fieldName()
                    + " into its base while the index was quiet: " + e.getMessage()
                    + ". Its changes stay logged and the fold is retried later.");
        }
    }

    private static String keyOf(String projectName, String collName, String identifier) {
        return Cache.getCollectionIdentifier(projectName, collName) + Globals.COLL_IDENTIFIER_SEPARATOR + identifier;
    }
}
