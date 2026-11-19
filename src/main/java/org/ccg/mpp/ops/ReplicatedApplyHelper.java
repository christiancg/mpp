package org.ccg.mpp.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.cache.WriterPkRows;
import org.ccg.mpp.cluster.ClusterConfig;
import org.ccg.mpp.cluster.ClusterCoordinator;
import org.ccg.mpp.cluster.HybridClock;
import org.ccg.mpp.cluster.PeerNames;
import org.ccg.mpp.cluster.msg.ReplicationPayload;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.fault.FaultInjector;
import org.ccg.mpp.fault.FaultPoint;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.fs.TombstoneView;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.listen.DirtySink;
import org.ccg.mpp.listen.ListenManager;
import org.ccg.mpp.listen.NotificationBatch;
import org.ccg.mpp.log.Logger;
import org.ccg.mpp.ops.admin.CollectionIncarnation;
import org.ccg.mpp.ops.req.BulkSaveRequest;
import org.ccg.mpp.ops.resp.BulkSaveResponse;

// Never re-replicates: replication fires only from the owner's OperationProcessor write handlers.
public final class ReplicatedApplyHelper {
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final HybridClock hybridClock = IocContainer.get(HybridClock.class);
    private static final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private static final ListenManager listenManager = IocContainer.get(ListenManager.class);
    private static final FaultInjector faultInjector = IocContainer.get(FaultInjector.class);
    @SuppressWarnings("FieldMayBeFinal")
    private static ClusterCoordinator coordinator = IocContainer.get(ClusterCoordinator.class);
    private static final Logger logger = Logger.logFor(ReplicatedApplyHelper.class);

    private ReplicatedApplyHelper() {
    }

    public static boolean apply(ReplicationPayload payload, long timeoutMillis) {
        if (payload == null || payload.getOp() == null
                || !PeerNames.collection(payload.getProjectName(), payload.getCollName())) {
            return false;
        }
        final var projectName = payload.getProjectName();
        final var collName = payload.getCollName();
        var locked = false;
        try {
            locked = locks.tryLockWrite(projectName, collName, timeoutMillis);
            if (!locked) {
                logger.warning("Skipping the replicated apply to " + projectName + "|" + collName
                        + ": its write lock stayed held for " + timeoutMillis + "ms");
                return false;
            }
            faultInjector.at(FaultPoint.REPLICATED_APPLY_LOCKED, Cache.getCollectionIdentifier(projectName, collName));
            return applyLocked(payload);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warning("Interrupted applying a replicated write to " + projectName + "|" + collName);
            return false;
        } catch (Exception e) {
            logger.error("Failed to apply replicated write to " + projectName + "|" + collName, e);
            return false;
        } finally {
            if (locked) {
                locks.release(projectName, collName);
            }
        }
    }

    static boolean applyLocked(ReplicationPayload payload) throws Exception {
        return applyWithin(payload, listenManager);
    }

    static boolean applyLocked(ReplicationPayload payload, NotificationBatch transactionBatch) throws Exception {
        return applyWithin(payload, transactionBatch);
    }

    private static boolean applyWithin(ReplicationPayload payload, DirtySink outer) throws Exception {
        if (!versionsWithinSkew(payload)) {
            return false;
        }
        if (belongsToAnotherIncarnation(payload)) {
            logger.warning("Refusing a replicated " + payload.getOp() + " to " + payload.getProjectName() + "|"
                    + payload.getCollName() + ": it belongs to incarnation " + payload.incarnationValue()
                    + " and this node holds incarnation "
                    + CollectionIncarnation.current(payload.getProjectName(), payload.getCollName()));
            return false;
        }
        try (var batch = listenManager.openBatch(outer,
                List.of(Cache.getCollectionIdentifier(payload.getProjectName(), payload.getCollName())))) {
            return switch (payload.getOp()) {
                case UPSERT -> applyUpsert(payload, batch);
                case DELETE -> applyDelete(payload, batch);
            };
        }
    }

    public static boolean belongsToAnotherIncarnation(ReplicationPayload payload) {
        final var stamped = payload.incarnationValue();
        return stamped != 0L
                && !CollectionIncarnation.isCurrent(payload.getProjectName(), payload.getCollName(), stamped);
    }

    private static boolean applyUpsert(ReplicationPayload payload, DirtySink sink) throws Exception {
        final var documents = payload.getDocuments();
        final var versions = payload.getVersions();
        final var acceptedDocuments = new ArrayList<JsonObject>();
        final var acceptedVersions = new ArrayList<Long>();
        final var tombstones = fs.tombstones().view(payload.getProjectName(), payload.getCollName());
        final var stored = storedRowsOf(payload.getProjectName(), payload.getCollName(),
                versionedIdsOf(documents, versions));
        for (var i = 0; i < documents.size(); i++) {
            final var document = documents.get(i);
            final var version = versionAt(versions, i);
            if (version == null) {
                acceptedDocuments.add(document);
                acceptedVersions.add(null);
                continue;
            }
            hybridClock.observe(version);
            if (isSupersededLocally(document, version, stored, tombstones)) {
                continue;
            }
            acceptedDocuments.add(document);
            acceptedVersions.add(version);
        }
        if (acceptedDocuments.isEmpty()) {
            return true;
        }
        final var request = new BulkSaveRequest(payload.getProjectName(), payload.getCollName());
        request.setObjects(acceptedDocuments);
        return SaveOperationHelper.executeBulkSave(request, acceptedVersions, sink,
                servesCollection(payload.getProjectName(), payload.getCollName())) instanceof BulkSaveResponse;
    }

    public static boolean versionsWithinSkew(ReplicationPayload payload) {
        if (payload.getVersions() == null) {
            return true;
        }
        for (final var raw : payload.getVersions()) {
            if (raw != null && !hybridClock.withinSkew(Long.parseLong(raw), clusterConfig.maxClockSkewMs())) {
                logger.warning("Refusing a replicated " + payload.getOp() + " to " + payload.getProjectName() + "|"
                        + payload.getCollName() + ": its version " + raw
                        + " is more than clusterMaxClockSkewMs ahead of this node's clock");
                return false;
            }
        }
        return true;
    }

    private static boolean isSupersededLocally(JsonObject document, long version, WriterPkRows stored,
            TombstoneView tombstones) {
        final var id = idOf(document);
        if (id == null) {
            return false;
        }
        final var row = stored.row(id);
        if (row != null && row.getVersion() > version) {
            return true;
        }
        final var tombstoned = tombstones.versionOf(id);
        return tombstoned != 0L && tombstoned >= version;
    }

    private static String idOf(JsonObject document) {
        return document.get(Globals.PK_FIELD) instanceof JsonString jsonString ? jsonString.getValue() : null;
    }

    private static List<String> versionedIdsOf(List<JsonObject> documents, List<String> versions) {
        final var ids = new ArrayList<String>(documents.size());
        for (var i = 0; i < documents.size(); i++) {
            final var id = idOf(documents.get(i));
            if (id != null && versionAt(versions, i) != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    private static WriterPkRows storedRowsOf(String projectName, String collName, Collection<String> ids)
            throws IOException {
        return ids.isEmpty() ? WriterPkRows.of(List.of()) : cache.pkRowsForWriter(projectName, collName, ids);
    }

    private static Long versionAt(List<String> versions, int index) {
        if (versions == null || index >= versions.size()) {
            return null;
        }
        final var raw = versions.get(index);
        return raw == null ? null : Long.valueOf(raw);
    }

    private static boolean applyDelete(ReplicationPayload payload, DirtySink sink) throws Exception {
        final var projectName = payload.getProjectName();
        final var collName = payload.getCollName();
        final var ids = payload.getIds();
        final var versions = payload.getVersions();
        final var tombstones = fs.tombstones().view(projectName, collName);
        final var storedRows = storedRowsOf(projectName, collName, ids);
        final var missingIds = new ArrayList<String>();
        final var missingVersions = new ArrayList<Long>();
        final var deletable = new ArrayList<String>(ids.size());
        for (var i = 0; i < ids.size(); i++) {
            final var id = ids.get(i);
            final var version = versionAt(versions, i);
            if (version != null) {
                hybridClock.observe(version);
                if (tombstones.versionOf(id) < version) {
                    missingIds.add(id);
                    missingVersions.add(version);
                }
            }
            final var stored = storedRows.row(id);
            if (version == null || stored == null || stored.getVersion() <= version) {
                deletable.add(id);
            }
        }
        final var pending = fs.tombstones().appendAllDeferred(projectName, collName, missingIds, missingVersions);
        faultInjector.at(FaultPoint.REPLICATED_TOMBSTONES_APPENDED,
                Cache.getCollectionIdentifier(projectName, collName));
        DeleteOperationHelper.executeBulkDelete(projectName, collName, deletable, sink,
                servesCollection(projectName, collName));
        pending.await();
        return true;
    }

    private static boolean servesCollection(String projectName, String collName) {
        return coordinator.stillOwns(projectName, collName);
    }
}
