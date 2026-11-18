package org.ccg.mpp.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.ccg.mpp.cluster.AdminAntiEntropyService;
import org.ccg.mpp.cluster.ClusterConfig;
import org.ccg.mpp.cluster.ClusterCoordinator;
import org.ccg.mpp.cluster.ReplicationOutcome;
import org.ccg.mpp.cluster.WriteGuard;
import org.ccg.mpp.cluster.admin.AdminRecords;
import org.ccg.mpp.cluster.ownership.OwnershipManager;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.log.Logger;
import org.ccg.mpp.ops.admin.AdminRecordKey;
import org.ccg.mpp.ops.admin.RestampedRecords;
import org.ccg.mpp.ops.req.OperationRequest;
import org.ccg.mpp.ops.req.ReindexRequest;
import org.ccg.mpp.ops.resp.OperationResponse;

public final class ClusterAdminHelper {
    private static final Set<OperationType> ADMIN_DDL = Set.of(OperationType.CREATE_DATABASE,
            OperationType.DROP_DATABASE, OperationType.CREATE_COLLECTION, OperationType.DROP_COLLECTION,
            OperationType.CREATE_INDEX, OperationType.DROP_INDEX, OperationType.REINDEX,
            OperationType.SET_DATABASE_OWNERS, OperationType.SAVE_SCHEMA, OperationType.DELETE_SCHEMA,
            OperationType.SAVE_PROCEDURE, OperationType.DELETE_PROCEDURE, OperationType.SAVE_TRIGGER,
            OperationType.DELETE_TRIGGER, OperationType.SAVE_SCHEDULE, OperationType.DELETE_SCHEDULE,
            OperationType.CREATE_USER, OperationType.DELETE_USER, OperationType.SET_PASSWORD,
            OperationType.CHANGE_PERMISSIONS);
    private static final Logger logger = Logger.logFor(ClusterAdminHelper.class);
    private static final ClusterCoordinator coordinator = IocContainer.get(ClusterCoordinator.class);
    private static final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private static final OwnershipManager ownershipManager = IocContainer.get(OwnershipManager.class);
    private static final AdminAntiEntropyService adminAntiEntropyService = IocContainer
            .get(AdminAntiEntropyService.class);

    private ClusterAdminHelper() {
    }

    public static boolean isCoordinatedAdminOp(OperationType type) {
        return ADMIN_DDL.contains(type);
    }

    public static OperationResponse guard(OperationRequest request) {
        if (!isCoordinatedAdminOp(request.getType())) {
            return null;
        }
        if (clusterConfig.isEnabled() && !ownershipManager.isAdminCoordinator()) {
            return new OperationResponse(request.getType(), ErrorCode.NOT_COLLECTION_OWNER);
        }
        if (coordinator.guardAdmin().kind() == WriteGuard.Kind.NO_QUORUM) {
            return new OperationResponse(request.getType(), ErrorCode.NO_QUORUM);
        }
        if (clusterConfig.isEnabled() && !adminAntiEntropyService.hasCompletedAdminSync()) {
            adminAntiEntropyService.reconcileSoon();
            return new OperationResponse(request.getType(), ErrorCode.ADMIN_SYNCING);
        }
        return null;
    }

    private static List<AdminRecordKey> replicatedKeys(OperationRequest request, List<AdminRecordKey> restamped) {
        final var keys = new ArrayList<>(AdminRecordKey.touchedBy(request));
        keys.addAll(restamped);
        return keys.stream().distinct().toList();
    }

    public static OperationResponse afterAdminOp(OperationRequest request, OperationResponse response,
            RestampedRecords restamped) {
        final var type = request.getType();
        if (!isCoordinatedAdminOp(type) || !clusterConfig.isEnabled() || response.getStatus() != OperationStatus.OK) {
            return response;
        }
        final ReplicationOutcome outcome;
        try {
            outcome = type == OperationType.REINDEX
                    ? coordinator.broadcastReindex((ReindexRequest) request)
                    : coordinator.replicateAdminRecords(AdminRecords.of(replicatedKeys(request, restamped.keys())));
        } catch (IOException unreadable) {
            logger.warning("The " + type + " committed here, but its admin records could not be read to replicate"
                    + " them; the admin anti-entropy sweep carries it: " + unreadable.getMessage());
            return new OperationResponse(type, ErrorCode.REPLICATION_TIMEOUT);
        }
        return switch (outcome) {
            case TIMEOUT, NOT_COORDINATOR, NOT_OWNER -> new OperationResponse(type, ErrorCode.REPLICATION_TIMEOUT);
            case NOT_CLUSTERED, QUORUM_MET -> response;
        };
    }
}
