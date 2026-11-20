package org.ccg.mpp.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import org.ccg.mpp.bckg_ops.events.EventType;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.DbEntry;
import org.ccg.mpp.data.TriggerDefinition;
import org.ccg.mpp.data.admin.AdminTriggerRunEntry;
import org.ccg.mpp.ex.PartialBulkSaveException;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.log.Logger;
import org.ccg.mpp.ops.req.BulkSaveRequest;
import org.ccg.mpp.ops.req.DeleteRequest;
import org.ccg.mpp.ops.req.SaveRequest;
import org.ccg.mpp.ops.resp.BulkSaveResponse;
import org.ccg.mpp.ops.resp.DeleteResponse;
import org.ccg.mpp.ops.resp.OperationResponse;
import org.ccg.mpp.ops.resp.SaveResponse;

public final class TriggerHelper {
    private static final int DOCUMENT_READ_RETRY_ATTEMPTS = 3;
    private static final long DOCUMENT_READ_RETRY_DELAY_MS = 25L;

    @SuppressWarnings("FieldMayBeFinal")
    private static Cache cache = IocContainer.get(Cache.class);
    private static final Configuration configuration = Configuration.getInstance();
    private static final Logger logger = Logger.logFor(TriggerHelper.class);

    private TriggerHelper() {
    }

    static List<DbEntry> readEntriesWithRetry(String projectName, String collName, Set<String> ids) throws IOException {
        IOException lastFailure = null;
        for (var attempt = 0; attempt < DOCUMENT_READ_RETRY_ATTEMPTS; attempt++) {
            try {
                return cache.getEntriesByIds(projectName, collName, ids);
            } catch (IOException e) {
                lastFailure = e;
                sleepBeforeRetry();
            }
        }
        throw lastFailure;
    }

    private static void sleepBeforeRetry() {
        try {
            Thread.sleep(DOCUMENT_READ_RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static List<org.ccg.mpp.data.TriggerDefinition> triggersOrNone(String projectName, String collName) {
        try {
            return cache.getTriggersFor(projectName, collName);
        } catch (org.ccg.mpp.ex.MetadataReadException e) {
            logger.warning("Could not read the triggers for " + projectName + "|" + collName
                    + "; firing none for this write: " + e.getMessage());
            return List.of();
        }
    }

    private static List<TriggerDefinition> matchingTriggers(String projectName, String collName, EventType type,
            int depth) {
        if (!configuration.isTriggersEnabled() || Globals.SCRIPT_RUNS_COLLECTION_NAME.equals(collName)) {
            return List.of();
        }
        final var triggers = triggersOrNone(projectName, collName);
        if (triggers.isEmpty()) {
            return List.of();
        }
        final var matching = new ArrayList<TriggerDefinition>();
        for (final var trigger : triggers) {
            if (!trigger.isBefore() && trigger.isEnabled() && trigger.getEvents().contains(type)
                    && (depth == 0 || trigger.isAllowCascade())) {
                matching.add(trigger);
            }
        }
        return matching;
    }

    private static List<List<DbEntry>> runGroups(TriggerDefinition trigger, List<DbEntry> entries) {
        return trigger.isBatchMode() ? List.of(entries) : entries.stream().map(List::of).toList();
    }

    public static void afterWrite(String projectName, String collName, EventType type, List<DbEntry> entries,
            String actingUser, int depth) {
        stageRuns(projectName, collName, type, entries, actingUser, depth,
                (_, _, descriptor) -> TriggerRunLog.record(descriptor)).submitAll();
    }

    private interface RunRecorder {
        String record(TriggerDefinition trigger, List<DbEntry> group, TriggerRunLog.TriggerRunDescriptor descriptor);
    }

    private static StagedTriggerRuns stageRuns(String projectName, String collName, EventType type,
            List<DbEntry> entries, String actingUser, int depth, RunRecorder recorder) {
        if (entries.isEmpty()) {
            return StagedTriggerRuns.none();
        }
        final var runs = new ArrayList<StagedTriggerRuns.StagedRun>();
        for (final var trigger : matchingTriggers(projectName, collName, type, depth)) {
            for (final var group : runGroups(trigger, entries)) {
                final var descriptor = new TriggerRunLog.TriggerRunDescriptor(projectName, collName, trigger.getName(),
                        trigger.getProcedureName(), type, trigger.isBatchMode(), actingUser, depth,
                        System.currentTimeMillis(), group);
                final var runId = recorder.record(trigger, group, descriptor);
                runs.add(new StagedTriggerRuns.StagedRun(projectName, collName, trigger.getName(),
                        trigger.getProcedureName(), type, trigger.isBatchMode(), actingUser, depth,
                        group.stream().map(DbEntry::get_id).toList(), group, runId, descriptor.firedAt()));
            }
        }
        return StagedTriggerRuns.of(runs);
    }

    public static StagedTriggerRuns stageSave(SaveRequest request, String actingUser) throws IOException {
        return stageSave(request, actingUser, WriteEffects.IMMEDIATE);
    }

    static StagedTriggerRuns stageSave(SaveRequest request, String actingUser, WriteEffects effects)
            throws IOException {
        final var projectName = request.getProjectName();
        final var collName = request.getCollectionName();
        if (firesOnNoSave(projectName, collName, request.getTriggerDepth())) {
            return StagedTriggerRuns.none();
        }
        final var id = BeforeHookHelper.resolveIdForWrite(request);
        return id == null
                ? StagedTriggerRuns.none()
                : stageBeforeSave(projectName, collName, List.of(id), actingUser, request.getTriggerDepth(),
                        effects.stagingSink());
    }

    public static StagedTriggerRuns stageBulkSave(BulkSaveRequest request, String actingUser) throws IOException {
        final var projectName = request.getProjectName();
        final var collName = request.getCollectionName();
        if (firesOnNoSave(projectName, collName, request.getTriggerDepth())) {
            return StagedTriggerRuns.none();
        }
        final var ids = new LinkedHashSet<String>();
        for (final var object : request.getObjects()) {
            final var id = BeforeHookHelper.resolveIdForWrite(object);
            if (id != null) {
                ids.add(id);
            }
        }
        final var effects = WriteEffects.batched();
        final var staged = stageBeforeSave(projectName, collName, List.copyOf(ids), actingUser,
                request.getTriggerDepth(), effects.stagingSink());
        effects.flushStaging(Cache.getCollectionIdentifier(projectName, collName));
        return staged;
    }

    public static StagedTriggerRuns stageDelete(DeleteRequest request, DbEntry captured, String actingUser)
            throws IOException {
        return stageDelete(request, captured, actingUser, WriteEffects.IMMEDIATE);
    }

    static StagedTriggerRuns stageDelete(DeleteRequest request, DbEntry captured, String actingUser,
            WriteEffects effects) throws IOException {
        if (captured == null) {
            return StagedTriggerRuns.none();
        }
        final var projectName = request.getProjectName();
        final var collName = request.getCollectionName();
        final var versions = priorVersionsOf(projectName, collName, List.of(captured.get_id()));
        return stageRuns(projectName, collName, EventType.DELETED, List.of(captured), actingUser,
                request.getTriggerDepth(),
                (_, _, descriptor) -> TriggerRunLog.recordStaged(descriptor, versions, effects.stagingSink()));
    }

    public static StagedTriggerRuns stageDeleteMany(DeleteRequest request, List<DbEntry> captured, String actingUser)
            throws IOException {
        if (captured.isEmpty()) {
            return StagedTriggerRuns.none();
        }
        final var projectName = request.getProjectName();
        final var collName = request.getCollectionName();
        final var versions = priorVersionsOf(projectName, collName, captured.stream().map(DbEntry::get_id).toList());
        final var effects = WriteEffects.batched();
        final var staged = stageRuns(projectName, collName, EventType.DELETED, captured, actingUser,
                request.getTriggerDepth(),
                (_, _, descriptor) -> TriggerRunLog.recordStaged(descriptor, versions, effects.stagingSink()));
        effects.flushStaging(Cache.getCollectionIdentifier(projectName, collName));
        return staged;
    }

    private static boolean firesOnNoSave(String projectName, String collName, int depth) {
        return hasNotTriggerFor(projectName, collName, EventType.CREATED, depth)
                && hasNotTriggerFor(projectName, collName, EventType.UPDATED, depth);
    }

    private static StagedTriggerRuns stageBeforeSave(String projectName, String collName, List<String> ids,
            String actingUser, int depth, TriggerRunLog.EntrySink sink) throws IOException {
        final var versions = priorVersionsOf(projectName, collName, ids);
        final var created = new ArrayList<DbEntry>();
        final var updated = new ArrayList<DbEntry>();
        for (final var id : ids) {
            final var placeholder = idOnlyEntry(projectName, collName, id);
            (versions.get(id) == AdminTriggerRunEntry.ABSENT_VERSION ? created : updated).add(placeholder);
        }
        final RunRecorder staged = (_, _, descriptor) -> TriggerRunLog.recordStaged(descriptor, versions, sink);
        return StagedTriggerRuns.combine(
                stageRuns(projectName, collName, EventType.CREATED, created, actingUser, depth, staged),
                stageRuns(projectName, collName, EventType.UPDATED, updated, actingUser, depth, staged));
    }

    private static DbEntry idOnlyEntry(String projectName, String collName, String id) {
        final var entry = new DbEntry();
        entry.setProjectName(projectName);
        entry.setCollectionName(collName);
        entry.set_id(id);
        return entry;
    }

    private static Map<String, Long> priorVersionsOf(String projectName, String collName, List<String> ids)
            throws IOException {
        final var primaryKeyIndex = cache.getPkIndexAndLoadIfNecessary(projectName, collName);
        final var versions = new LinkedHashMap<String, Long>();
        for (final var id : ids) {
            final var position = Collections.binarySearch(primaryKeyIndex, id);
            versions.put(id,
                    position >= 0 ? primaryKeyIndex.get(position).getVersion() : AdminTriggerRunEntry.ABSENT_VERSION);
        }
        return versions;
    }

    public static StagedTriggerRuns stageCommittedIds(String projectName, String collName, EventType type,
            List<String> ids, String actingUser, int depth, String txId, WriteEffects batch) {
        return stageCommitted(projectName, collName, type, committedEntries(projectName, collName, type, ids, depth),
                actingUser, depth, txId, batch);
    }

    public static StagedTriggerRuns stageCommitted(String projectName, String collName, EventType type,
            List<DbEntry> entries, String actingUser, int depth, String txId, WriteEffects batch) {
        return stageRuns(projectName, collName, type, entries, actingUser, depth,
                (trigger, group, descriptor) -> TriggerRunLog.recordDeterministic(
                        descriptor, txId, TriggerRunLog.deterministicRunId(txId, projectName, collName,
                                trigger.getName(), type, trigger.isBatchMode() ? null : group.getFirst().get_id()),
                        batch.stagingSink()));
    }

    public static <T> T discardingOnFailure(StagedTriggerRuns staged, Callable<T> step) throws Exception {
        return discardingOnFailure(staged, WriteEffects.IMMEDIATE, step);
    }

    static <T> T discardingOnFailure(StagedTriggerRuns staged, WriteEffects effects, Callable<T> step)
            throws Exception {
        try {
            return step.call();
        } catch (Exception e) {
            effects.discard(staged);
            throw e;
        }
    }

    public static OperationResponse runStaged(StagedTriggerRuns staged, String projectName, String collName,
            String actingUser, int depth, Callable<OperationResponse> write) throws Exception {
        return runStaged(staged, projectName, collName, actingUser, depth, WriteEffects.IMMEDIATE, write);
    }

    static OperationResponse runStaged(StagedTriggerRuns staged, String projectName, String collName, String actingUser,
            int depth, WriteEffects effects, Callable<OperationResponse> write) throws Exception {
        final OperationResponse response;
        try {
            response = write.call();
        } catch (PartialBulkSaveException e) {
            effects.submitLanded(staged, landedIdsOf(e.committed(), staged), projectName, collName, actingUser, depth);
            throw e;
        } catch (Exception e) {
            effects.discard(staged);
            throw e;
        }
        effects.submitLanded(staged, landedIdsOf(response, staged), projectName, collName, actingUser, depth);
        return response;
    }

    private static Map<EventType, Set<String>> landedIdsOf(OperationResponse response, StagedTriggerRuns staged) {
        return switch (response) {
            case SaveResponse save ->
                Map.of(save.isInserted() ? EventType.CREATED : EventType.UPDATED, Set.of(save.get_id()));
            case BulkSaveResponse bulk ->
                Map.of(EventType.CREATED, idSet(bulk.getInserted()), EventType.UPDATED, idSet(bulk.getUpdated()));
            case DeleteResponse _ -> Map.of(EventType.DELETED, staged.stagedDeleteIds());
            case null, default -> Map.of();
        };
    }

    private static Set<String> idSet(List<String> ids) {
        return ids == null ? Set.of() : new LinkedHashSet<>(ids);
    }

    public static void afterWriteIds(String projectName, String collName, EventType type, List<String> ids,
            String actingUser, int depth) {
        afterWrite(projectName, collName, type, committedEntries(projectName, collName, type, ids, depth), actingUser,
                depth);
    }

    private static List<DbEntry> committedEntries(String projectName, String collName, EventType type, List<String> ids,
            int depth) {
        if (ids == null || ids.isEmpty() || hasNotTriggerFor(projectName, collName, type, depth)) {
            return List.of();
        }
        try {
            return readEntriesWithRetry(projectName, collName, new HashSet<>(ids));
        } catch (IOException e) {
            logger.error("Could not read the committed documents to fire a trigger on " + projectName + "|" + collName,
                    e);
            return List.of();
        }
    }

    public static DbEntry captureForDelete(String projectName, String collName, String id, int depth) {
        if (hasNotTriggerFor(projectName, collName, EventType.DELETED, depth)) {
            return null;
        }
        try {
            final var entries = readEntriesWithRetry(projectName, collName, Set.of(id));
            return entries.isEmpty() ? null : entries.getFirst();
        } catch (IOException e) {
            logger.error(
                    "Could not read the document being deleted to fire a trigger on " + projectName + "|" + collName,
                    e);
            return null;
        }
    }

    public static boolean firesOnDelete(String projectName, String collName, int depth) {
        return !hasNotTriggerFor(projectName, collName, EventType.DELETED, depth);
    }

    public static boolean firesOnSave(String projectName, String collName, int depth) {
        return !hasNotTriggerFor(projectName, collName, EventType.CREATED, depth)
                || !hasNotTriggerFor(projectName, collName, EventType.UPDATED, depth);
    }

    private static boolean hasNotTriggerFor(String projectName, String collName, EventType type, int depth) {
        return matchingTriggers(projectName, collName, type, depth).isEmpty();
    }
}
