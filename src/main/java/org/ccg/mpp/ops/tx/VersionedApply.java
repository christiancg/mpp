package org.ccg.mpp.ops.tx;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.PkIndexEntry;
import org.ccg.mpp.data.admin.AdminTransactionEntry;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.listen.DirtySink;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.DeleteOperationHelper;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.SaveOperationHelper;
import org.ccg.mpp.ops.TriggerRunLog;
import org.ccg.mpp.ops.req.BulkSaveRequest;
import org.ccg.mpp.ops.req.DeleteRequest;
import org.ccg.mpp.ops.req.SaveRequest;
import org.ccg.mpp.ops.resp.OperationResponse;

public final class VersionedApply {
    public enum ApplyOutcome {
        APPLIED, ALREADY_APPLIED, SUPERSEDED;

        private static final long UNVERSIONED = 0L;

        public static ApplyOutcome forSave(long storedVersion, long tombstoneVersion, long opVersion) {
            if (opVersion <= UNVERSIONED) {
                return APPLIED;
            }
            if (storedVersion == opVersion) {
                return ALREADY_APPLIED;
            }
            if (storedVersion > opVersion || tombstoneVersion >= opVersion) {
                return SUPERSEDED;
            }
            return APPLIED;
        }

        public static ApplyOutcome forDelete(long storedVersion, boolean present, long opVersion) {
            if (!present) {
                return ALREADY_APPLIED;
            }
            if (opVersion <= UNVERSIONED) {
                return APPLIED;
            }
            return storedVersion > opVersion ? SUPERSEDED : APPLIED;
        }

        public boolean firesTriggers() {
            return this != SUPERSEDED;
        }
    }

    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);

    private final Map<String, Map<String, Long>> tombstonesByCollection = new HashMap<>();
    private final Map<String, ApplyOutcome> outcomes = new HashMap<>();
    private final DirtySink sink;
    private CommitWriteGroups groups = CommitWriteGroups.NONE;

    public VersionedApply(DirtySink sink) {
        this.sink = sink;
    }

    void groupWritesIn(CommitWriteGroups commitGroups) {
        this.groups = commitGroups;
    }

    public Map<String, ApplyOutcome> outcomes() {
        return outcomes;
    }

    public static String key(String projectName, String collName, String id) {
        return Cache.getCollectionIdentifier(projectName, collName) + Globals.COLL_IDENTIFIER_SEPARATOR + id;
    }

    public void apply(AdminTransactionEntry op) throws Exception {
        final var projectName = op.getTargetProject();
        final var collName = op.getTargetColl();
        if (CommitWriteGroups.writesData(op)) {
            groups.ensureOpen(projectName, collName);
        }
        switch (op.getOpType()) {
            case AdminTransactionEntry.OP_TYPE_SAVE ->
                applySave(projectName, collName, op.getPayload(), op.versionAt(0));
            case AdminTransactionEntry.OP_TYPE_BULK_SAVE -> applyBulkSave(op);
            case AdminTransactionEntry.OP_TYPE_DELETE ->
                applyDelete(projectName, collName, op.targetId(), op.versionAt(0));
            case AdminTransactionEntry.OP_TYPE_DELETE_TRIGGER_RUN ->
                AdminOperationHelper.deleteTriggerRuns(TriggerRunLog.recordIdsFor(op.consumedTriggerRunId()));
            default -> throw new IllegalStateException("Unknown transaction op type: " + op.getOpType());
        }
    }

    private void applySave(String projectName, String collName, JsonObject object, long version) throws Exception {
        final var id = idOf(object);
        if (record(projectName, collName, id,
                saveOutcome(projectName, collName, id, version)) != ApplyOutcome.APPLIED) {
            return;
        }
        final var request = new SaveRequest(projectName, collName);
        request.setObject(object);
        request.set_id(id);
        requireApplied(AdminTransactionEntry.OP_TYPE_SAVE, SaveOperationHelper.executeSave(request, version, sink));
    }

    private void applyBulkSave(AdminTransactionEntry op) throws Exception {
        final var projectName = op.getTargetProject();
        final var collName = op.getTargetColl();
        final var objects = new ArrayList<JsonObject>();
        final var versions = new ArrayList<Long>();
        final var elements = op.savedObjects();
        for (var i = 0; i < elements.size(); i++) {
            final var object = elements.get(i);
            final var id = idOf(object);
            final var version = op.versionAt(i);
            if (record(projectName, collName, id,
                    saveOutcome(projectName, collName, id, version)) == ApplyOutcome.APPLIED) {
                objects.add(object);
                versions.add(version);
            }
        }
        if (objects.isEmpty()) {
            return;
        }
        final var request = new BulkSaveRequest(projectName, collName);
        request.setObjects(objects);
        requireApplied(AdminTransactionEntry.OP_TYPE_BULK_SAVE,
                SaveOperationHelper.executeBulkSave(request, versions, sink));
    }

    private void applyDelete(String projectName, String collName, String id, long version) throws Exception {
        final var stored = storedEntry(projectName, collName, id);
        final var outcome = ApplyOutcome.forDelete(stored == null ? 0L : stored.getVersion(), stored != null, version);
        if (record(projectName, collName, id, outcome) != ApplyOutcome.APPLIED) {
            return;
        }
        final var request = new DeleteRequest(projectName, collName);
        request.set_id(id);
        DeleteOperationHelper.executeDelete(request, sink);
    }

    private ApplyOutcome record(String projectName, String collName, String id, ApplyOutcome outcome) {
        outcomes.put(key(projectName, collName, id), outcome);
        return outcome;
    }

    private ApplyOutcome saveOutcome(String projectName, String collName, String id, long version) throws IOException {
        final var stored = storedEntry(projectName, collName, id);
        if (stored != null) {
            return ApplyOutcome.forSave(stored.getVersion(), 0L, version);
        }
        return ApplyOutcome.forSave(0L, tombstoneVersion(projectName, collName, id), version);
    }

    private long tombstoneVersion(String projectName, String collName, String id) throws IOException {
        final var collId = Cache.getCollectionIdentifier(projectName, collName);
        var tombstones = tombstonesByCollection.get(collId);
        if (tombstones == null) {
            tombstones = fs.tombstones().read(projectName, collName);
            tombstonesByCollection.put(collId, tombstones);
        }
        return tombstones.getOrDefault(id, 0L);
    }

    public static PkIndexEntry storedEntry(String projectName, String collName, String id) throws IOException {
        final var primaryKeyIndex = cache.getPkIndexAndLoadIfNecessary(projectName, collName);
        final var position = Collections.binarySearch(primaryKeyIndex, id);
        return position >= 0 ? primaryKeyIndex.get(position) : null;
    }

    private static String idOf(JsonObject object) {
        return object.get(Globals.PK_FIELD).asJsonString().getValue();
    }

    private static void requireApplied(String opType, OperationResponse response) {
        if (response == null || response.getStatus() == OperationStatus.OK) {
            return;
        }
        throw new IllegalStateException(
                "A buffered " + opType + " operation failed while committing: " + response.getMessage());
    }
}
