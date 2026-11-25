package org.ccg.mpp.ops;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.Set;
import org.ccg.mpp.bckg_ops.BackgroundTaskManager;
import org.ccg.mpp.bckg_ops.ScheduleExecutor;
import org.ccg.mpp.bckg_ops.ScheduleRegistry;
import org.ccg.mpp.bckg_ops.TriggerExecutor;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.cache.CacheableResource;
import org.ccg.mpp.cluster.ScriptPlacement;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.data.admin.AdminUserEntry;
import org.ccg.mpp.ejson.elements.JsonArray;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.fs.ProjectFileStore;
import org.ccg.mpp.fs.TombstoneStore;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.log.Logger;
import org.ccg.mpp.ops.resp.GetProjectStatsResponse;
import org.ccg.mpp.ops.resp.OperationResponse;

public final class ProjectStatsHelper {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final BackgroundTaskManager backgroundTaskManager = IocContainer.get(BackgroundTaskManager.class);
    private static final TriggerExecutor triggerExecutor = IocContainer.get(TriggerExecutor.class);
    private static final ScheduleExecutor scheduleExecutor = IocContainer.get(ScheduleExecutor.class);
    private static final ScheduleRegistry scheduleRegistry = IocContainer.get(ScheduleRegistry.class);
    private static final ScriptLoad scriptLoad = IocContainer.get(ScriptLoad.class);
    private static final ScriptRunRegistry scriptRunRegistry = IocContainer.get(ScriptRunRegistry.class);
    private static final Configuration configuration = Configuration.getInstance();
    private static final CompiledScriptCache compiledScripts = IocContainer.get(CompiledScriptCache.class);
    private static final ScriptAdmission scriptAdmission = IocContainer.get(ScriptAdmission.class);
    private static final ScriptPlacement scriptPlacement = IocContainer.get(ScriptPlacement.class);
    private static final ProjectFileStore projectFiles = IocContainer.get(ProjectFileStore.class);
    private static final Logger logger = Logger.logFor(ProjectStatsHelper.class);

    private ProjectStatsHelper() {
    }

    public static OperationResponse processGetProjectStats(String actingUser) {
        return OperationResponse.respondOrError(OperationType.GET_PROJECT_STATS, ErrorCode.ERROR_GATHERING_STATS,
                () -> new GetProjectStatsResponse("Ok", build(StatsView.of(userOf(actingUser)))));
    }

    private static AdminUserEntry userOf(String actingUser) {
        return actingUser == null ? null : cache.getAdminUserEntry(actingUser);
    }

    private static JsonObject build(StatsView view) {
        final var stats = new JsonObject();
        if (view.includesNodeSections()) {
            stats.add("memory", buildMemoryStats());
            stats.add("inDoubtTransactions", buildInDoubtTransactions());
            stats.add("triggers", buildTriggerStats());
            stats.add("schedules", buildScheduleStats());
            stats.add("scripts", buildScriptStats());
            stats.add("background", buildBackgroundStats());
        }
        final var projectArray = new JsonArray();
        final var totals = new Totals();
        var projectCount = 0;
        for (var projectName : cache.getUserProjectNames()) {
            if (view.includesProject(projectName)) {
                projectArray.add(buildProjectStats(view, projectName, totals));
                projectCount++;
            }
        }
        stats.add("totals", buildTotals(view, totals, projectCount));
        stats.add("projects", projectArray);
        return stats;
    }

    private static JsonObject buildTriggerStats() {
        final var triggers = new JsonObject();
        triggers.addProperty("enabled", configuration.isTriggersEnabled());
        triggers.addProperty("fired", triggerExecutor.getFired());
        triggers.addProperty("failed", triggerExecutor.getFailed());
        triggers.addProperty("dropped", triggerExecutor.getDropped());
        triggers.addProperty("queued", (long) triggerExecutor.getQueued());
        triggers.addProperty("retried", triggerExecutor.getRetried());
        triggers.addProperty("deadLettered", triggerExecutor.getDeadLettered());
        triggers.addProperty("runLogEnabled", configuration.isTriggerRunLogEnabled());
        triggers.addProperty("beforeApplied", BeforeHookContext.getApplied());
        triggers.addProperty("beforeReplaced", BeforeHookContext.getReplaced());
        triggers.addProperty("beforeRejected", BeforeHookContext.getRejected());
        triggers.addProperty("beforeFailed", BeforeHookContext.getFailed());
        triggers.addProperty("pendingRuns", (long) TriggerRunLog.pendingRecordCount());
        return triggers;
    }

    private static JsonObject buildScheduleStats() {
        final var schedules = new JsonObject();
        schedules.addProperty("enabled", configuration.isSchedulesEnabled());
        schedules.addProperty("registered", (long) scheduleRegistry.size());
        schedules.addProperty("fired", scheduleExecutor.getFired());
        schedules.addProperty("failed", scheduleExecutor.getFailed());
        schedules.addProperty("skipped", scheduleExecutor.getSkipped());
        schedules.addProperty("dropped", scheduleExecutor.getDropped());
        schedules.addProperty("queued", (long) scheduleExecutor.getQueued());
        return schedules;
    }

    private static JsonObject buildScriptStats() {
        final var scripts = new JsonObject();
        scripts.addProperty("routingEnabled", configuration.isScriptRoutingEnabled());
        scripts.addProperty("running", (long) scriptLoad.current());
        scripts.addProperty("capacity", (long) scriptAdmission.capacity());
        scripts.addProperty("available", (long) scriptAdmission.available());
        scripts.addProperty("rejected", scriptAdmission.getRejected());
        scripts.addProperty("capacityPerUser", (long) scriptAdmission.perUserCapacity());
        scripts.addProperty("capacityPerProject", (long) scriptAdmission.perProjectCapacity());
        scripts.addProperty("rejectedPerUser", scriptAdmission.getRejectedPerUser());
        scripts.addProperty("rejectedPerProject", scriptAdmission.getRejectedPerProject());
        scripts.addProperty("waited", scriptAdmission.getWaited());
        scripts.addProperty("forwarded", scriptPlacement.getForwarded());
        scripts.addProperty("forwardFallbacks", scriptPlacement.getForwardFallbacks());
        scripts.addProperty("outcomeUnknown", scriptPlacement.getOutcomeUnknown());
        scripts.addProperty("localityWeight", (long) configuration.getScriptLocalityWeight());
        scripts.addProperty("localityPreferred", scriptPlacement.getLocalityPreferred());
        scripts.addProperty("cancelled", scriptRunRegistry.getCancelled());
        scripts.addProperty("compiledCacheEntries", (long) compiledScripts.size());
        scripts.addProperty("historyRecorded", ScriptRunHistory.getRecorded());
        scripts.addProperty("historyDropped", ScriptRunHistory.getDropped());
        scripts.addProperty("historyQueued", (long) backgroundTaskManager.queuedHistory());
        return scripts;
    }

    private static JsonObject buildBackgroundStats() {
        final var background = new JsonObject();
        background.addProperty("pending", (long) backgroundTaskManager.gatedPending());
        background.addProperty("maintenance", (long) backgroundTaskManager.maintenancePending());
        return background;
    }

    private static JsonObject buildInDoubtTransactions() {
        final var inDoubt = new JsonObject();
        final var ids = new JsonArray();
        for (final var dtxId : Tx2pcLog.preparedDtxIds()) {
            ids.add(new org.ccg.mpp.ejson.elements.JsonString(dtxId));
        }
        inDoubt.addProperty("count", (long) ids.size());
        inDoubt.add("ids", ids);
        return inDoubt;
    }

    private static JsonObject buildMemoryStats() {
        final var memory = new JsonObject();
        final var heapUsage = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        memory.addProperty("heapUsedBytes", heapUsage.getUsed());
        memory.addProperty("heapMaxBytes", heapUsage.getMax());
        memory.addProperty("heapCommittedBytes", heapUsage.getCommitted());
        long userCacheBytes = 0L;
        for (CacheableResource r : cache.listCacheableResources()) {
            userCacheBytes += r.estimatedSizeBytes();
        }
        memory.addProperty("userCacheBytes", userCacheBytes);
        memory.addProperty("residentTombstoneBytes", TombstoneStore.residentBytes());
        memory.addProperty("maxMemoryBytes", configuration.getMaxMemoryBytes());
        memory.addProperty("cachingDisabled", configuration.isCachingDisabled());
        memory.addProperty("cacheUnlimited", configuration.isCacheUnlimited());
        memory.add("adminMetadataCache", buildAdminMetadataCacheStats());
        return memory;
    }

    private static JsonObject buildAdminMetadataCacheStats() {
        final var stats = cache.metadataCacheStats();
        final var json = new JsonObject();
        json.addProperty("procedureBytes", stats.procedureBytes());
        json.addProperty("procedureEntries", (long) stats.procedureEntries());
        json.addProperty("triggerBytes", stats.triggerBytes());
        json.addProperty("triggerEntries", (long) stats.triggerEntries());
        json.addProperty("schemaBytes", stats.schemaBytes());
        json.addProperty("schemaEntries", (long) stats.schemaEntries());
        json.addProperty("scheduleBytes", stats.scheduleBytes());
        json.addProperty("scheduleEntries", (long) stats.scheduleEntries());
        json.addProperty("entrypointBytes", stats.entrypointBytes());
        json.addProperty("entrypointEntries", (long) stats.entrypointEntries());
        json.addProperty("middlewareBytes", stats.middlewareBytes());
        json.addProperty("middlewareEntries", (long) stats.middlewareEntries());
        json.addProperty("missEntries", (long) stats.missEntries());
        return json;
    }

    private static JsonObject buildFileStats(String projectName) {
        final var files = new JsonObject();
        try {
            final var fileTotals = projectFiles.totals(projectName);
            files.addProperty("entries", fileTotals.entries());
            files.addProperty("bytes", fileTotals.bytes());
        } catch (IOException | RuntimeException e) {
            logger.warning("The files of project '" + projectName + "' could not be counted: " + e.getMessage());
            files.addProperty("entries", 0L);
            files.addProperty("bytes", 0L);
            files.addProperty("unreadable", true);
        }
        return files;
    }

    private static JsonObject buildProjectStats(StatsView view, String projectName, Totals totals) {
        final var collNames = cache.getCollectionNamesForProject(projectName).stream()
                .filter(collName -> view.includesCollection(projectName, collName)).toList();
        final var collArray = new JsonArray();
        long projectIndexes = 0L;
        long projectPages = 0L;
        long dbEntries = 0L;
        long projectSizeBytes = 0L;
        for (var collName : collNames) {
            final var coll = buildCollectionStats(projectName, collName);
            collArray.add(coll.json());
            projectIndexes += coll.indexCount();
            projectPages += coll.pageCount();
            dbEntries += coll.entryCount();
            projectSizeBytes += coll.sizeBytes();
        }
        final var projectObj = new JsonObject();
        projectObj.addProperty("name", projectName);
        projectObj.addProperty("collectionCount", (long) collNames.size());
        projectObj.addProperty("indexCount", projectIndexes);
        projectObj.addProperty("pageCount", projectPages);
        projectObj.addProperty("entryCount", dbEntries);
        projectObj.addProperty("sizeBytes", projectSizeBytes);
        projectObj.add("collections", collArray);
        if (view.includesFiles(projectName)) {
            projectObj.add("files", buildFileStats(projectName));
        }

        totals.collections += collNames.size();
        totals.indexes += projectIndexes;
        totals.pages += projectPages;
        totals.entries += dbEntries;
        totals.sizeBytes += projectSizeBytes;
        return projectObj;
    }

    private static CollectionStats buildCollectionStats(String projectName, String collName) {
        final var collEntry = cache.getAdminCollectionEntry(projectName, collName);
        final var indexes = collEntry == null ? Set.<String>of() : collEntry.getIndexes();
        final var stored = StoredTotals.ofCollection(projectName, collName);
        final var pageCount = stored.pages();
        final var entryCount = stored.entries();
        final var sizeBytes = stored.sizeBytes();
        final var collObj = new JsonObject();
        collObj.addProperty("name", collName);
        collObj.addProperty("indexCount", (long) indexes.size());
        final var idxArr = new JsonArray();
        for (var idx : indexes) {
            idxArr.add(idx);
        }
        collObj.add("indexes", idxArr);
        collObj.addProperty("pageCount", pageCount);
        collObj.addProperty("entryCount", entryCount);
        collObj.addProperty("sizeBytes", sizeBytes);
        return new CollectionStats(collObj, indexes.size(), pageCount, entryCount, sizeBytes);
    }

    private static JsonObject buildTotals(StatsView view, Totals totals, int projectCount) {
        final var json = new JsonObject();
        if (view.includesNodeSections()) {
            json.addProperty("userCount", (long) cache.getAllAdminUserEntries().size());
        }
        json.addProperty("projectCount", (long) projectCount);
        json.addProperty("collectionCount", totals.collections);
        json.addProperty("indexCount", totals.indexes);
        json.addProperty("pageCount", totals.pages);
        json.addProperty("entryCount", totals.entries);
        json.addProperty("sizeBytes", totals.sizeBytes);
        return json;
    }

    private static final class Totals {
        long collections;
        long indexes;
        long pages;
        long entries;
        long sizeBytes;
    }

    private record CollectionStats(JsonObject json, long indexCount, long pageCount, long entryCount, long sizeBytes) {
    }
}
