package org.ccg.mpp.cluster;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.cluster.admin.AdminRecord;
import org.ccg.mpp.cluster.files.FilePayloads;
import org.ccg.mpp.cluster.msg.AdminSnapshotPayload;
import org.ccg.mpp.cluster.msg.ReplicationOp;
import org.ccg.mpp.cluster.msg.ReplicationPayload;
import org.ccg.mpp.cluster.msg.TxReplicationPayload;
import org.ccg.mpp.cluster.ownership.OwnershipManager;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.Transaction;
import org.ccg.mpp.ejson.EJson;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.fault.FaultInjector;
import org.ccg.mpp.fault.FaultPoint;
import org.ccg.mpp.fs.FileChange;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.fs.PendingForce;
import org.ccg.mpp.fs.ProjectFileStore;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.log.Logger;
import org.ccg.mpp.ops.admin.CollectionIncarnation;
import org.ccg.mpp.ops.req.ReindexRequest;
import org.ccg.mpp.ops.tx.VersionedApply;
import org.ccg.mpp.ops.tx.VersionedApply.ApplyOutcome;

public class ClusterCoordinator {
    public record DeleteReservation(boolean ownerLost, Long version, PendingForce tombstonesForced) {
        private static final DeleteReservation NOT_CLUSTERED = new DeleteReservation(false, null);
        private static final DeleteReservation OWNER_LOST = new DeleteReservation(true, null);

        public DeleteReservation(boolean ownerLost, Long version) {
            this(ownerLost, version, PendingForce.NONE);
        }

        public void awaitTombstones() throws IOException {
            tombstonesForced.await();
        }
    }

    private final Logger logger = Logger.logFor(ClusterCoordinator.class);
    private final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private final OwnershipManager ownershipManager = IocContainer.get(OwnershipManager.class);
    private final Replicator replicator = IocContainer.get(Replicator.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final ProjectFileStore projectFiles = IocContainer.get(ProjectFileStore.class);
    private final HybridClock hybridClock = IocContainer.get(HybridClock.class);
    private final EJson eJson = IocContainer.get(EJson.class);
    private final FaultInjector faultInjector = IocContainer.get(FaultInjector.class);

    public WriteGuard guardWrite(String projectName, String collName) {
        if (doesntCoordinate(projectName)) {
            return WriteGuard.allow();
        }
        if (!ownershipManager.hasQuorum()) {
            return WriteGuard.noQuorum();
        }
        if (!ownershipManager.isOwner(projectName, collName)) {
            return WriteGuard.notOwner(ownershipManager.ownerAddress(projectName, collName));
        }
        return WriteGuard.allow();
    }

    public ReplicationOutcome replicateUpsert(String projectName, String collName, List<String> ids) {
        final var applicability = documentReplicationOutcome(projectName, collName);
        if (applicability != null) {
            return applicability;
        }
        try {
            final var documents = new ArrayList<JsonObject>();
            final var versions = new ArrayList<String>();
            readDocuments(projectName, collName, ids, documents, versions);
            return replicator.broadcast(stamped(
                    new ReplicationPayload(projectName, collName, ReplicationOp.UPSERT, documents, null, versions)));
        } catch (Exception e) {
            // The local commit stands; a failure to ship it is reported to the client and reconciled later.
            logger.warning("Failed to replicate upsert to " + projectName + "|" + collName + ": " + e.getMessage());
            return ReplicationOutcome.TIMEOUT;
        }
    }

    public DeleteReservation reserveDelete(String projectName, String collName, List<String> ids)
            throws java.io.IOException {
        final var applicability = documentReplicationOutcome(projectName, collName);
        if (applicability == ReplicationOutcome.NOT_OWNER) {
            return DeleteReservation.OWNER_LOST;
        }
        if (applicability != null) {
            return DeleteReservation.NOT_CLUSTERED;
        }
        final var version = hybridClock.next();
        final var rows = cache.pkRowsForWriter(projectName, collName, ids);
        final var held = new ArrayList<String>(ids.size());
        for (final var id : ids) {
            if (rows.row(id) != null) {
                held.add(id);
            }
        }
        final var forced = fs.tombstones().reserve(projectName, collName, held, version);
        faultInjector.at(FaultPoint.DELETE_TOMBSTONE_RESERVED, Cache.getCollectionIdentifier(projectName, collName));
        return new DeleteReservation(false, version, forced);
    }

    public void retractDelete(String projectName, String collName, List<String> ids, Long reservedVersion)
            throws java.io.IOException {
        if (reservedVersion == null) {
            return;
        }
        for (final var id : ids) {
            fs.tombstones().retract(projectName, collName, id, reservedVersion);
        }
    }

    public ReplicationOutcome replicateDelete(String projectName, String collName, List<String> ids,
            Long reservedVersion) {
        final var applicability = documentReplicationOutcome(projectName, collName);
        if (applicability != null) {
            return applicability;
        }
        final var version = reservedVersion != null ? reservedVersion : hybridClock.next();
        final var versions = new ArrayList<>(Collections.nCopies(ids.size(), Long.toString(version)));
        return replicator.broadcast(
                stamped(new ReplicationPayload(projectName, collName, ReplicationOp.DELETE, null, ids, versions)));
    }

    public ReplicationOutcome replicateGroup(String projectName, String collName, List<String> upsertIds,
            Map<String, Long> deleteVersions) {
        final var applicability = documentReplicationOutcome(projectName, collName);
        if (applicability != null) {
            return applicability;
        }
        try {
            final var entries = new ArrayList<ReplicationPayload>(2);
            if (!deleteVersions.isEmpty()) {
                final var versions = new ArrayList<String>(deleteVersions.size());
                for (final var version : deleteVersions.values()) {
                    versions.add(Long.toString(version != null ? version : hybridClock.next()));
                }
                entries.add(stamped(new ReplicationPayload(projectName, collName, ReplicationOp.DELETE, null,
                        List.copyOf(deleteVersions.keySet()), versions)));
            }
            final var documents = new ArrayList<JsonObject>();
            final var versions = new ArrayList<String>();
            readDocuments(projectName, collName, upsertIds, documents, versions);
            if (!documents.isEmpty()) {
                entries.add(stamped(new ReplicationPayload(projectName, collName, ReplicationOp.UPSERT, documents, null,
                        versions)));
            }
            return switch (entries.size()) {
                case 0 -> ReplicationOutcome.QUORUM_MET;
                case 1 -> replicator.broadcast(entries.getFirst());
                default -> replicator.broadcastTx(new TxReplicationPayload(entries));
            };
        } catch (Exception e) {
            logger.warning(
                    "Failed to replicate a write group to " + projectName + "|" + collName + ": " + e.getMessage());
            return ReplicationOutcome.TIMEOUT;
        }
    }

    public ReplicationOutcome replicateFiles(String projectName, List<FileChange> changes, byte[] content) {
        final var applicability = documentReplicationOutcome(projectName, Globals.FILES_LOCK_NAME);
        if (applicability != null) {
            return applicability;
        }
        try {
            return replicator.broadcastFiles(
                    FilePayloads.replication(projectName, projectFiles.incarnation(projectName), changes, content));
        } catch (Exception e) {
            logger.warning("Failed to replicate a file change of " + projectName + ": " + e.getMessage());
            return ReplicationOutcome.TIMEOUT;
        }
    }

    // A clustered transaction must not commit without a write quorum (split-brain protection). Always true
    // when clustering is off, so the standalone commit path is unchanged.
    public boolean hasNotTransactionQuorum() {
        return clusterConfig.isEnabled() && !ownershipManager.hasQuorum();
    }

    public void reserveTransactionTombstones(Transaction transaction) throws IOException {
        if (!clusterConfig.isEnabled()) {
            return;
        }
        for (final var collId : List.copyOf(transaction.touchedCollections())) {
            final var overlay = transaction.overlayFor(collId);
            if (overlay == null) {
                continue;
            }
            final var parts = collId.split(Globals.COLL_IDENTIFIER_SEPARATOR_REGEX, 2);
            for (final var id : deletedIds(overlay)) {
                reserveTombstone(transaction, collId, parts[0], parts[1], id);
            }
        }
    }

    private void reserveTombstone(Transaction transaction, String collId, String projectName, String collName,
            String id) throws IOException {
        final var version = transaction.versionOf(collId, id);
        final var stored = VersionedApply.storedEntry(projectName, collName, id);
        final var outcome = ApplyOutcome.forDelete(stored == null ? 0L : stored.getVersion(), stored != null, version);
        if (outcome == ApplyOutcome.SUPERSEDED) {
            transaction.forget(collId, id);
            return;
        }
        final var tombstoneVersion = version > 0 ? version : hybridClock.next();
        transaction.recordDelete(collId, id, tombstoneVersion);
        fs.tombstones().append(projectName, collName, id, tombstoneVersion);
    }

    private static List<String> deletedIds(Map<String, JsonObject> overlay) {
        final var deleteIds = new ArrayList<String>();
        for (final var overlayEntry : overlay.entrySet()) {
            if (Transaction.isTombstone(overlayEntry.getValue())) {
                deleteIds.add(overlayEntry.getKey());
            }
        }
        return deleteIds;
    }

    public ReplicationOutcome replicateTransaction(Transaction transaction) {
        if (!clusterConfig.isEnabled()) {
            return ReplicationOutcome.NOT_CLUSTERED;
        }
        final var entries = new ArrayList<ReplicationPayload>();
        try {
            for (final var collId : transaction.touchedCollections()) {
                final var parts = collId.split(Globals.COLL_IDENTIFIER_SEPARATOR_REGEX, 2);
                // Only replicate collections this node owns — a 2PC participant owns its slice's collections
                // and replicates them to their replicas; a collection owned elsewhere is that owner's to ship.
                if (ownershipManager.isOwner(parts[0], parts[1])) {
                    buildCollectionEntries(parts[0], parts[1], transaction, collId, entries);
                }
            }
        } catch (Exception e) {
            logger.warning("Failed to build transaction replication batch: " + e.getMessage());
            return ReplicationOutcome.TIMEOUT;
        }
        if (entries.isEmpty()) {
            return ReplicationOutcome.NOT_CLUSTERED;
        }
        return replicator.broadcastTx(new TxReplicationPayload(entries));
    }

    private static ReplicationPayload stamped(ReplicationPayload payload) {
        payload.setIncarnationValue(CollectionIncarnation.current(payload.getProjectName(), payload.getCollName()));
        return payload;
    }

    private void buildCollectionEntries(String projectName, String collName, Transaction transaction, String collId,
            List<ReplicationPayload> entries) throws Exception {
        final var overlay = transaction.overlayFor(collId);
        final var upsertIds = new ArrayList<String>();
        final var deleteIds = deletedIds(overlay);
        for (final var overlayEntry : overlay.entrySet()) {
            if (!Transaction.isTombstone(overlayEntry.getValue())) {
                upsertIds.add(overlayEntry.getKey());
            }
        }
        if (!upsertIds.isEmpty()) {
            final var documents = new ArrayList<JsonObject>();
            final var versions = new ArrayList<String>();
            readDocuments(projectName, collName, upsertIds, documents, versions);
            entries.add(stamped(
                    new ReplicationPayload(projectName, collName, ReplicationOp.UPSERT, documents, null, versions)));
        }
        if (!deleteIds.isEmpty()) {
            final var versions = new ArrayList<String>();
            for (final var id : deleteIds) {
                versions.add(Long.toString(transaction.versionOf(collId, id)));
            }
            entries.add(stamped(
                    new ReplicationPayload(projectName, collName, ReplicationOp.DELETE, null, deleteIds, versions)));
        }
    }

    // A node without a write quorum must not apply admin/DDL ops (split-brain protection).
    public WriteGuard guardAdmin() {
        if (!clusterConfig.isEnabled()) {
            return WriteGuard.allow();
        }
        return ownershipManager.hasQuorum() ? WriteGuard.allow() : WriteGuard.noQuorum();
    }

    public ReplicationOutcome replicateAdminRecords(List<AdminRecord> records) {
        return replicateAdmin(() -> replicator.broadcastAdmin(new AdminSnapshotPayload(records)));
    }

    public ReplicationOutcome broadcastReindex(ReindexRequest request) {
        return replicateAdmin(() -> replicator.broadcastReindex(eJson.toJson(request)));
    }

    private ReplicationOutcome replicateAdmin(Supplier<ReplicationOutcome> broadcast) {
        if (!clusterConfig.isEnabled()) {
            return ReplicationOutcome.NOT_CLUSTERED;
        }
        if (!ownershipManager.isAdminCoordinator()) {
            return ReplicationOutcome.NOT_COORDINATOR;
        }
        return broadcast.get();
    }

    private boolean doesntCoordinate(String projectName) {
        return !clusterConfig.isEnabled() || Globals.ADMIN_PROJECT_NAME.equals(projectName);
    }

    private ReplicationOutcome documentReplicationOutcome(String projectName, String collName) {
        if (doesntCoordinate(projectName)) {
            return ReplicationOutcome.NOT_CLUSTERED;
        }
        if (!ownershipManager.isOwner(projectName, collName)) {
            return ReplicationOutcome.NOT_OWNER;
        }
        return null;
    }

    public boolean stillOwns(String projectName, String collName) {
        return doesntCoordinate(projectName) || ownershipManager.isOwner(projectName, collName);
    }

    private void readDocuments(String projectName, String collName, List<String> ids, List<JsonObject> documents,
            List<String> versions) throws Exception {
        final var primaryKeyIndex = cache.getPkIndexAndLoadIfNecessary(projectName, collName);
        for (final var id : ids) {
            final var position = Collections.binarySearch(primaryKeyIndex, id);
            if (position >= 0) {
                final var indexEntry = primaryKeyIndex.get(position);
                documents.add(cache.getById(projectName, collName, indexEntry).getData());
                versions.add(Long.toString(indexEntry.getVersion()));
            }
        }
    }
}
