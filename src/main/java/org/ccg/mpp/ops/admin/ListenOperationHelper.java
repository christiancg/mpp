package org.ccg.mpp.ops.admin;

import java.util.UUID;
import org.ccg.mpp.conn.ClientTracker;
import org.ccg.mpp.ejson.EJson;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.listen.ListenManager;
import org.ccg.mpp.listen.ResultHasher;
import org.ccg.mpp.ops.AggregationOperationHelper;
import org.ccg.mpp.ops.CollectionAccessHelper;
import org.ccg.mpp.ops.CollectionReadinessGuard;
import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.OperationLocks;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.req.AggregateRequest;
import org.ccg.mpp.ops.req.ListenRequest;
import org.ccg.mpp.ops.req.StopListenRequest;
import org.ccg.mpp.ops.resp.ListenResponse;
import org.ccg.mpp.ops.resp.OperationResponse;
import org.ccg.mpp.ops.resp.StopListenResponse;

public final class ListenOperationHelper {
    private static final ListenManager listenManager = IocContainer.get(ListenManager.class);
    private static final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private static final EJson eJson = IocContainer.get(EJson.class);

    private ListenOperationHelper() {
    }

    public static OperationResponse processListenOperation(ListenRequest listenRequest, UUID clientId) {
        if (!listenManager.hasCapacityFor(clientId)) {
            return new OperationResponse(OperationType.LISTEN, ErrorCode.TOO_MANY_LISTENS);
        }
        final var username = clientTracker.getAuthenticatedUsername(clientId);
        if (!listenManager.hasUserCapacity(username)) {
            return new OperationResponse(OperationType.LISTEN, ErrorCode.TOO_MANY_LISTENS_FOR_USER);
        }
        final var retainedBytes = retainedBytesOf(listenRequest);
        final var refusal = listenManager.reserve(username, retainedBytes);
        if (refusal != null) {
            return new OperationResponse(OperationType.LISTEN, refusal);
        }
        OperationResponse response = null;
        try {
            response = registerReserved(listenRequest, clientId, username, retainedBytes);
            return response;
        } finally {
            if (!(response instanceof ListenResponse)) {
                listenManager.release(username, retainedBytes);
            }
        }
    }

    private static long retainedBytesOf(ListenRequest listenRequest) {
        return eJson.toJson(listenRequest).length();
    }

    private static OperationResponse registerReserved(ListenRequest listenRequest, UUID clientId, String username,
            long retainedBytes) {
        final var projectName = listenRequest.getProjectName();
        final var collName = listenRequest.getCollectionName();
        final var aggReq = new AggregateRequest(projectName, collName);
        aggReq.setAggregationSteps(listenRequest.getAggregationSteps());
        final var notRegistered = CollectionReadinessGuard.precheckRead(OperationType.LISTEN, projectName,
                AggregationOperationHelper.aggregateCollections(aggReq));
        if (notRegistered != null) {
            return notRegistered;
        }
        return OperationLocks.withReadLocks(false, AggregationOperationHelper.aggregateLockSet(aggReq),
                OperationType.LISTEN, ErrorCode.ERROR_LISTEN, () -> {
                    final var unregistered = CollectionReadinessGuard.checkRead(OperationType.LISTEN, projectName,
                            AggregationOperationHelper.aggregateCollections(aggReq));
                    if (unregistered != null) {
                        return unregistered;
                    }
                    final var results = AggregationOperationHelper.processAggregation(aggReq);
                    final var ordered = ResultHasher.ordersResults(listenRequest.getAggregationSteps());
                    final var initialHash = ResultHasher.hash(results, ordered);
                    final var dirtyReq = new AggregateRequest(projectName, collName);
                    dirtyReq.setAggregationSteps(listenRequest.getAggregationSteps());
                    dirtyReq.setDirtyRead(true);
                    CollectionAccessHelper.recordCollectionAccess(projectName, collName);
                    final var listenId = listenManager.register(clientId, username, dirtyReq, initialHash,
                            retainedBytes);
                    return new ListenResponse(listenId.toString(), results, initialHash, false);
                });
    }

    public static OperationResponse processStopListenOperation(StopListenRequest request, UUID clientId) {
        return OperationResponse.respondOrError(OperationType.STOP_LISTEN, ErrorCode.ERROR_LISTEN, () -> {
            final var listenId = java.util.UUID.fromString(request.getListenId());
            final var unregistered = listenManager.unregister(listenId, clientId);
            if (!unregistered) {
                return new OperationResponse(OperationType.STOP_LISTEN, ErrorCode.LISTEN_NOT_FOUND);
            }
            return new StopListenResponse();
        });
    }
}
