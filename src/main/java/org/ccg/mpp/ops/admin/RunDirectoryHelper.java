package org.ccg.mpp.ops.admin;

import org.ccg.mpp.data.admin.TriggerRunStatus;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.req.CancelScriptRequest;
import org.ccg.mpp.ops.req.ListTriggerRunsRequest;
import org.ccg.mpp.ops.req.ResolveTransactionRequest;
import org.ccg.mpp.ops.req.ResolveTriggerRunRequest;
import org.ccg.mpp.ops.resp.CancelScriptResponse;
import org.ccg.mpp.ops.resp.ListScriptsResponse;
import org.ccg.mpp.ops.resp.ListTransactionsResponse;
import org.ccg.mpp.ops.resp.ListTriggerRunsResponse;
import org.ccg.mpp.ops.resp.OperationResponse;
import org.ccg.mpp.ops.resp.ResolveTriggerRunResponse;

public final class RunDirectoryHelper {

    private static final org.ccg.mpp.cluster.Tx2pcCoordinator tx2pcCoordinator = IocContainer
            .get(org.ccg.mpp.cluster.Tx2pcCoordinator.class);
    private static final org.ccg.mpp.cluster.Tx2pcDirectory tx2pcDirectory = IocContainer
            .get(org.ccg.mpp.cluster.Tx2pcDirectory.class);
    private static final org.ccg.mpp.cluster.ScriptRunDirectory scriptRunDirectory = IocContainer
            .get(org.ccg.mpp.cluster.ScriptRunDirectory.class);
    private static final org.ccg.mpp.cluster.TriggerRunDirectory triggerRunDirectory = IocContainer
            .get(org.ccg.mpp.cluster.TriggerRunDirectory.class);

    private RunDirectoryHelper() {
    }

    public static OperationResponse processResolveTransaction(ResolveTransactionRequest request) {
        final var commit = ResolveTransactionRequest.DECISION_COMMIT.equals(request.getDecision());
        return tx2pcCoordinator.forceResolve(request.getDtxId(), commit);
    }

    public static OperationResponse processListScripts() {
        return OperationResponse.respondOrError(OperationType.LIST_SCRIPTS, ErrorCode.SCRIPT_FAILED,
                () -> new ListScriptsResponse("Ok", scriptRunDirectory.listClusterWide()));
    }

    public static OperationResponse processCancelScript(CancelScriptRequest request) {
        return OperationResponse.respondOrError(OperationType.CANCEL_SCRIPT, ErrorCode.SCRIPT_FAILED,
                () -> new CancelScriptResponse("Ok", scriptRunDirectory.cancelClusterWide(request.getRunId())));
    }

    public static OperationResponse processListTriggerRuns(ListTriggerRunsRequest request) {
        return OperationResponse.respondOrError(OperationType.LIST_TRIGGER_RUNS, ErrorCode.SCRIPT_FAILED, () -> {
            final var filter = request.getStatus() == null || request.getStatus().isBlank()
                    ? null
                    : TriggerRunStatus.valueOf(request.getStatus().toUpperCase(java.util.Locale.ROOT));
            return new ListTriggerRunsResponse("Ok", triggerRunDirectory.listClusterWide(filter));
        });
    }

    public static OperationResponse processResolveTriggerRun(ResolveTriggerRunRequest request) {
        return OperationResponse.respondOrError(OperationType.RESOLVE_TRIGGER_RUN, ErrorCode.SCRIPT_FAILED,
                () -> new ResolveTriggerRunResponse("Ok",
                        triggerRunDirectory.resolveClusterWide(request.getRunId(), request.getDecision())));
    }

    public static OperationResponse processListTransactions() {
        return OperationResponse.respondOrError(OperationType.LIST_TRANSACTIONS, ErrorCode.ERROR_TRANSACTION,
                () -> new ListTransactionsResponse("Ok", tx2pcDirectory.listInDoubtClusterWide()));
    }
}
