package org.ccg.mpp.ops.index;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.ccg.mpp.bckg_ops.BackgroundTaskManager;
import org.ccg.mpp.bckg_ops.PendingIndexWrites;
import org.ccg.mpp.bckg_ops.events.IndexRepairEvent;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.DbEntry;
import org.ccg.mpp.ex.IndexLockBusyException;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.log.Logger;
import org.ccg.mpp.ops.admin.CollectionIncarnation;

public final class IndexRepairPass {
    private static final Logger logger = Logger.logFor(IndexRepairPass.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);
    private static final Configuration configuration = Configuration.getInstance();
    @SuppressWarnings("FieldMayBeFinal")
    private static ResourceLocking rl = IocContainer.get(ResourceLocking.class);
    @SuppressWarnings("FieldMayBeFinal")
    private static BackgroundTaskManager taskManager = IocContainer.get(BackgroundTaskManager.class);

    private enum Progress {
        DONE, BUSY, COLLECTION_GONE, FIELD_GONE
    }

    private final IndexRepairEvent event;
    private final String projectName;
    private final String collName;
    private final List<String> ids;

    private IndexRepairPass(IndexRepairEvent event) {
        this.event = event;
        this.projectName = event.getProjectName();
        this.collName = event.getCollName();
        this.ids = new ArrayList<>(new LinkedHashSet<>(event.getIds()));
    }

    public static void run(IndexRepairEvent event) throws InterruptedException {
        new IndexRepairPass(event).repair();
    }

    private void repair() throws InterruptedException {
        final var started = System.nanoTime();
        try {
            if (isStale()) {
                clearPending();
                return;
            }
            final var repaired = new LinkedHashSet<>(event.getRepairedFields());
            for (final var field : fieldsToRepair(repaired)) {
                final var progress = repairField(field, field.equals(event.getResumeField()) ? event.getResumeAt() : 0,
                        repaired);
                if (progress == Progress.BUSY) {
                    return;
                }
                if (progress == Progress.COLLECTION_GONE) {
                    clearPending();
                    return;
                }
                repaired.add(field);
            }
            clearPending();
            logger.info("Repaired the field indexes of " + Cache.getCollectionIdentifier(projectName, collName)
                    + " for " + ids.size() + " document(s) in " + (System.nanoTime() - started) / 1_000_000L + " ms");
        } catch (IOException e) {
            logger.warning("Failed to repair the field indexes of "
                    + Cache.getCollectionIdentifier(projectName, collName) + ": " + e.getMessage()
                    + ". Its documents stay pending, so index-backed reads re-test them;"
                    + " run REINDEX on that collection.");
        }
    }

    private List<String> fieldsToRepair(Set<String> repaired) {
        final var fields = new ArrayList<String>();
        for (final var field : MaintainedIndexes.of(projectName, collName)) {
            if (!repaired.contains(field)) {
                fields.add(field);
            }
        }
        fields.sort(null);
        return fields;
    }

    private Progress repairField(String field, int start, Set<String> repaired)
            throws IOException, InterruptedException {
        final var held = new HeldTypeLists();
        for (var at = start; at < ids.size(); at += Globals.REPAIR_PASS_CHUNK) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Interrupted while repairing the field indexes of "
                        + Cache.getCollectionIdentifier(projectName, collName));
            }
            final var progress = repairChunk(field,
                    ids.subList(at, Math.min(ids.size(), at + Globals.REPAIR_PASS_CHUNK)), held);
            if (progress == Progress.BUSY) {
                resubmit(repaired, field, at);
            }
            if (progress != Progress.DONE) {
                return progress;
            }
            Thread.yield();
        }
        return Progress.DONE;
    }

    private Progress repairChunk(String field, List<String> chunk, HeldTypeLists held)
            throws IOException, InterruptedException {
        if (!rl.tryLockRead(projectName, collName, configuration.getTransactionLockTimeoutMs())) {
            return Progress.BUSY;
        }
        final List<DeferredFold> folds;
        try {
            if (isStale()) {
                return Progress.COLLECTION_GONE;
            }
            if (!MaintainedIndexes.of(projectName, collName).contains(field)) {
                return Progress.FIELD_GONE;
            }
            final var byId = HashMap.<String, DbEntry>newHashMap(chunk.size());
            for (final var doc : cache.getEntriesByIds(projectName, collName, new HashSet<>(chunk))) {
                byId.put(doc.get_id(), doc);
            }
            folds = FieldMaintenance.maintain(projectName, collName, field, chunk, byId, null, held,
                    new CanonicalIds(projectName, collName));
        } catch (IndexLockBusyException busy) {
            return Progress.BUSY;
        } finally {
            rl.releaseRead(projectName, collName);
        }
        FieldIndexFolds.runAfterUnlock(folds, held::folded);
        return Progress.DONE;
    }

    private void resubmit(Set<String> repaired, String field, int at) {
        taskManager.submitMaintenanceFollowOn(new IndexRepairEvent(projectName, collName, event.getIds(),
                event.getIncarnation(), event.getPendingGeneration(), repaired, field, at));
    }

    private boolean isStale() {
        return !CollectionIncarnation.isCurrent(projectName, collName, event.getIncarnation());
    }

    private void clearPending() {
        pendingIndexWrites.clear(projectName, collName, event.getIds(), event.getPendingGeneration());
    }
}
