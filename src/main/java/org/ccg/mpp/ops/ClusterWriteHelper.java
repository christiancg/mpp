package org.ccg.mpp.ops;

import java.util.ArrayList;
import java.util.List;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.cluster.ClusterCoordinator;
import org.ccg.mpp.cluster.ClusterCoordinator.DeleteReservation;
import org.ccg.mpp.cluster.ReplicationOutcome;
import org.ccg.mpp.cluster.WriteGuard;
import org.ccg.mpp.fault.FaultInjector;
import org.ccg.mpp.fault.FaultPoint;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.listen.ListenManager;
import org.ccg.mpp.ops.req.DeleteRequest;
import org.ccg.mpp.ops.resp.BulkSaveResponse;
import org.ccg.mpp.ops.resp.DeleteManyResponse;
import org.ccg.mpp.ops.resp.DeleteResponse;
import org.ccg.mpp.ops.resp.OperationResponse;
import org.ccg.mpp.ops.resp.SaveResponse;

public final class ClusterWriteHelper {
    private static final ClusterCoordinator coordinator = IocContainer.get(ClusterCoordinator.class);
    private static final FaultInjector faultInjector = IocContainer.get(FaultInjector.class);
    private static final ListenManager listenManager = IocContainer.get(ListenManager.class);

    public record ReservedDelete(List<String> removed, Long version) {
    }

    private ClusterWriteHelper() {
    }

    public static OperationResponse guard(OperationType type, String projectName, String collName) {
        final var refusal = errorFor(type, coordinator.guardWrite(projectName, collName));
        return refusal != null ? refusal : CollectionReadinessGuard.precheck(type, projectName, collName);
    }

    private static OperationResponse errorFor(OperationType type, WriteGuard guard) {
        return switch (guard.kind()) {
            case ALLOW -> null;
            case NO_QUORUM -> new OperationResponse(type, ErrorCode.NO_QUORUM);
            case NOT_OWNER -> guard.ownerAddress() != null
                    ? new OperationResponse(type, ErrorCode.NOT_COLLECTION_OWNER, "owner=" + guard.ownerAddress())
                    : new OperationResponse(type, ErrorCode.NOT_COLLECTION_OWNER);
        };
    }

    public static OperationResponse afterSave(String projectName, String collName, OperationResponse response) {
        if (response instanceof SaveResponse saveResponse) {
            return withReplication(response, OperationType.SAVE,
                    coordinator.replicateUpsert(projectName, collName, List.of(saveResponse.get_id())));
        }
        return response;
    }

    static OperationResponse afterSave(String projectName, String collName, OperationResponse response,
            WriteEffects effects) {
        if (response instanceof SaveResponse saveResponse && effects.defersReplication()) {
            effects.replicateUpsertLater(saveResponse.get_id());
            return response;
        }
        return afterSave(projectName, collName, response);
    }

    public static OperationResponse afterBulkSave(String projectName, String collName, OperationResponse response) {
        if (response instanceof BulkSaveResponse bulkSaveResponse) {
            final var ids = new ArrayList<>(bulkSaveResponse.getInserted());
            ids.addAll(bulkSaveResponse.getUpdated());
            return withReplication(response, OperationType.BULK_SAVE,
                    coordinator.replicateUpsert(projectName, collName, ids));
        }
        return response;
    }

    public static DeleteReservation reserveDelete(String projectName, String collName, String id)
            throws java.io.IOException {
        final var reservation = coordinator.reserveDelete(projectName, collName, List.of(id));
        try {
            reservation.awaitTombstones();
        } catch (java.io.IOException e) {
            coordinator.retractDelete(projectName, collName, List.of(id), reservation.version());
            throw e;
        }
        return reservation;
    }

    public static OperationResponse deleteOrRetract(DeleteRequest request, Long reservedVersion) throws Exception {
        final OperationResponse local;
        try {
            local = DeleteOperationHelper.executeDelete(request);
        } catch (Exception e) {
            retractDelete(request, reservedVersion);
            throw e;
        }
        if (!(local instanceof DeleteResponse)) {
            retractDelete(request, reservedVersion);
        }
        return local;
    }

    private static void retractDelete(DeleteRequest request, Long reservedVersion) throws java.io.IOException {
        coordinator.retractDelete(request.getProjectName(), request.getCollectionName(), List.of(request.get_id()),
                reservedVersion);
    }

    public static OperationResponse afterDelete(String projectName, String collName, String id, Long reservedVersion,
            OperationResponse response) {
        if (response instanceof DeleteResponse) {
            return withReplication(response, OperationType.DELETE,
                    coordinator.replicateDelete(projectName, collName, List.of(id), reservedVersion));
        }
        return response;
    }

    static OperationResponse afterDelete(String projectName, String collName, String id, Long reservedVersion,
            OperationResponse response, WriteEffects effects) {
        if (response instanceof DeleteResponse && effects.defersReplication()) {
            effects.replicateDeleteLater(id, reservedVersion,
                    () -> coordinator.retractDelete(projectName, collName, List.of(id), reservedVersion));
            return response;
        }
        return afterDelete(projectName, collName, id, reservedVersion, response);
    }

    public static ReservedDelete deleteReserved(String projectName, String collName, List<String> ids)
            throws Exception {
        final var reservation = coordinator.reserveDelete(projectName, collName, ids);
        if (reservation.ownerLost()) {
            return null;
        }
        try {
            reservation.awaitTombstones();
        } catch (java.io.IOException e) {
            coordinator.retractDelete(projectName, collName, ids, reservation.version());
            throw e;
        }
        return new ReservedDelete(bulkDeleteOrRetract(coordinator, projectName, collName, ids, reservation.version()),
                reservation.version());
    }

    static ReservedDelete deleteReservedBesideForce(ClusterCoordinator through, String projectName, String collName,
            List<String> ids) throws Exception {
        final var reservation = through.reserveDelete(projectName, collName, ids);
        if (reservation.ownerLost()) {
            return null;
        }
        final var removed = bulkDeleteOrRetract(through, projectName, collName, ids, reservation.version());
        reservation.awaitTombstones();
        return new ReservedDelete(removed, reservation.version());
    }

    private static List<String> bulkDeleteOrRetract(ClusterCoordinator through, String projectName, String collName,
            List<String> ids, Long reservedVersion) throws Exception {
        try {
            return DeleteOperationHelper.executeBulkDelete(projectName, collName, ids, listenManager, true);
        } catch (Exception e) {
            through.retractDelete(projectName, collName, ids, reservedVersion);
            throw e;
        }
    }

    static ReplicationOutcome replicateReserved(ClusterCoordinator through, String projectName, String collName,
            ReservedDelete reserved) {
        return reserved.removed().isEmpty()
                ? ReplicationOutcome.NOT_CLUSTERED
                : through.replicateDelete(projectName, collName, reserved.removed(), reserved.version());
    }

    public static OperationResponse afterDeleteMany(String projectName, String collName, ReservedDelete reserved,
            OperationResponse response) {
        if (response instanceof DeleteManyResponse && reserved != null) {
            return withReplication(response, OperationType.DELETE,
                    replicateReserved(coordinator, projectName, collName, reserved));
        }
        return response;
    }

    static OperationResponse afterGroupReplication(OperationResponse response, ReplicationOutcome outcome) {
        if (response instanceof SaveResponse || response instanceof DeleteResponse) {
            return withReplication(response, response.getType(), outcome);
        }
        return response;
    }

    private static OperationResponse withReplication(OperationResponse success, OperationType type,
            ReplicationOutcome outcome) {
        return switch (outcome) {
            case TIMEOUT -> new OperationResponse(type, ErrorCode.REPLICATION_TIMEOUT);
            case NOT_OWNER, NOT_COORDINATOR -> new OperationResponse(type, ErrorCode.WRITE_OUTCOME_UNKNOWN);
            case NOT_CLUSTERED, QUORUM_MET -> success;
        };
    }

    public static OperationResponse refusalUnderLock(OperationType type, String projectName, String collName) {
        if (!coordinator.stillOwns(projectName, collName)) {
            return new OperationResponse(type, ErrorCode.NOT_COLLECTION_OWNER);
        }
        final var subject = Cache.getCollectionIdentifier(projectName, collName);
        faultInjector.at(FaultPoint.WRITE_OWNERSHIP_RECHECKED, subject);
        faultInjector.at(FaultPoint.WRITE_OWNER_PROBED, subject);
        return CollectionReadinessGuard.check(type, projectName, collName);
    }
}
