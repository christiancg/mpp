package org.ccg.mpp.ops;

import java.util.UUID;
import org.ccg.mpp.conn.ClientTracker;
import org.ccg.mpp.data.Transaction;
import org.ccg.mpp.ex.PartialBulkSaveException;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.admin.CollectionOperationHelper;
import org.ccg.mpp.ops.admin.ListenOperationHelper;
import org.ccg.mpp.ops.admin.ProjectOperationHelper;
import org.ccg.mpp.ops.admin.ReadPathHelper;
import org.ccg.mpp.ops.admin.RestampedRecords;
import org.ccg.mpp.ops.admin.RunDirectoryHelper;
import org.ccg.mpp.ops.index.IndexOperationHelper;
import org.ccg.mpp.ops.req.AggregateRequest;
import org.ccg.mpp.ops.req.ArmFaultRequest;
import org.ccg.mpp.ops.req.AuthenticateRequest;
import org.ccg.mpp.ops.req.AwaitFaultRequest;
import org.ccg.mpp.ops.req.BulkSaveRequest;
import org.ccg.mpp.ops.req.CallProcedureRequest;
import org.ccg.mpp.ops.req.CancelScriptRequest;
import org.ccg.mpp.ops.req.ChangePermissionsRequest;
import org.ccg.mpp.ops.req.CreateCollectionRequest;
import org.ccg.mpp.ops.req.CreateIndexRequest;
import org.ccg.mpp.ops.req.CreateProjectRequest;
import org.ccg.mpp.ops.req.CreateUserRequest;
import org.ccg.mpp.ops.req.DeleteProcedureRequest;
import org.ccg.mpp.ops.req.DeleteRequest;
import org.ccg.mpp.ops.req.DeleteScheduleRequest;
import org.ccg.mpp.ops.req.DeleteSchemaRequest;
import org.ccg.mpp.ops.req.DeleteTriggerRequest;
import org.ccg.mpp.ops.req.DeleteUserRequest;
import org.ccg.mpp.ops.req.DropCollectionRequest;
import org.ccg.mpp.ops.req.DropIndexRequest;
import org.ccg.mpp.ops.req.DropProjectRequest;
import org.ccg.mpp.ops.req.FindByIdRequest;
import org.ccg.mpp.ops.req.GetSchemaRequest;
import org.ccg.mpp.ops.req.ListCollectionsRequest;
import org.ccg.mpp.ops.req.ListProceduresRequest;
import org.ccg.mpp.ops.req.ListSchedulesRequest;
import org.ccg.mpp.ops.req.ListTriggerRunsRequest;
import org.ccg.mpp.ops.req.ListTriggersRequest;
import org.ccg.mpp.ops.req.ListUsersRequest;
import org.ccg.mpp.ops.req.ListenRequest;
import org.ccg.mpp.ops.req.OperationRequest;
import org.ccg.mpp.ops.req.ReadSnapshotRequest;
import org.ccg.mpp.ops.req.ReindexRequest;
import org.ccg.mpp.ops.req.ReleaseFaultRequest;
import org.ccg.mpp.ops.req.ReleaseSnapshotRequest;
import org.ccg.mpp.ops.req.ResolveTransactionRequest;
import org.ccg.mpp.ops.req.ResolveTriggerRunRequest;
import org.ccg.mpp.ops.req.RunScriptRequest;
import org.ccg.mpp.ops.req.SaveProcedureRequest;
import org.ccg.mpp.ops.req.SaveRequest;
import org.ccg.mpp.ops.req.SaveScheduleRequest;
import org.ccg.mpp.ops.req.SaveSchemaRequest;
import org.ccg.mpp.ops.req.SaveTriggerRequest;
import org.ccg.mpp.ops.req.ServerInfoRequest;
import org.ccg.mpp.ops.req.SetPasswordRequest;
import org.ccg.mpp.ops.req.SetProjectOwnersRequest;
import org.ccg.mpp.ops.req.StartSnapshotRequest;
import org.ccg.mpp.ops.req.StopListenRequest;
import org.ccg.mpp.ops.req.TestTriggerRequest;
import org.ccg.mpp.ops.resp.CloseConnectionResponse;
import org.ccg.mpp.ops.resp.OperationResponse;
import org.ccg.mpp.ops.resp.SaveWhereResponse;
import org.ccg.mpp.ops.snapshot.SnapshotOperationHelper;

public class OperationProcessor {
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);

    public OperationResponse processMessage(OperationRequest operationRequest) {
        return processMessage(operationRequest, null);
    }

    public OperationResponse processMessage(OperationRequest operationRequest, UUID clientId) {
        final var activeTransaction = clientTracker.getActiveTransaction(clientId);
        if (activeTransaction != null
                && !TransactionOperationHelper.isAllowedDuringTransaction(operationRequest.getType())) {
            return new OperationResponse(operationRequest.getType(), ErrorCode.OPERATION_NOT_ALLOWED_IN_TRANSACTION);
        }
        if (activeTransaction != null && activeTransaction.isAborted()
                && !TransactionOperationHelper.isAllowedOnAbortedTransaction(operationRequest.getType())) {
            return new OperationResponse(operationRequest.getType(), ErrorCode.TRANSACTION_NOT_USABLE);
        }
        final var actingUser = clientTracker.getAuthenticatedUsername(clientId);
        return processOperation(operationRequest, clientId, activeTransaction, actingUser);
    }

    private OperationResponse processOperation(OperationRequest operationRequest, UUID clientId,
            Transaction activeTransaction, String actingUser) {
        final var adminGuard = ClusterAdminHelper.guard(operationRequest);
        if (adminGuard != null) {
            return adminGuard;
        }
        final var restamped = new RestampedRecords();
        final var response = switch (operationRequest.getType()) {
            case BULK_SAVE ->
                processBulkSaveOperation((BulkSaveRequest) operationRequest, activeTransaction, actingUser);
            case SAVE -> processSaveOperation((SaveRequest) operationRequest, activeTransaction, actingUser);
            case FIND_BY_ID -> processFindByIdOperation((FindByIdRequest) operationRequest, activeTransaction);
            case AGGREGATE -> processAggregateOperation((AggregateRequest) operationRequest, activeTransaction);
            case DELETE -> processDeleteOperation((DeleteRequest) operationRequest, activeTransaction, actingUser);
            case CREATE_PROJECT -> processCreateProjectOperation((CreateProjectRequest) operationRequest, clientId);
            case DROP_PROJECT -> processDropProjectOperation((DropProjectRequest) operationRequest, restamped);
            case LIST_PROJECTS -> processListProjectsOperation(actingUser);
            case CREATE_COLLECTION -> processCreateCollectionOperation((CreateCollectionRequest) operationRequest);
            case DROP_COLLECTION -> processDropCollectionOperation((DropCollectionRequest) operationRequest, restamped);
            case LIST_COLLECTIONS -> processListCollectionsOperation((ListCollectionsRequest) operationRequest);
            case CREATE_INDEX -> processCreateIndex((CreateIndexRequest) operationRequest);
            case DROP_INDEX -> processDropIndex((DropIndexRequest) operationRequest);
            case REINDEX -> processReindex((ReindexRequest) operationRequest);
            case SAVE_SCHEMA -> processSaveSchema((SaveSchemaRequest) operationRequest);
            case DELETE_SCHEMA -> processDeleteSchema((DeleteSchemaRequest) operationRequest);
            case GET_SCHEMA -> processGetSchema((GetSchemaRequest) operationRequest);
            case CLOSE_CONNECTION -> new CloseConnectionResponse();
            case SERVER_INFO -> processServerInfo((ServerInfoRequest) operationRequest, actingUser);
            case AUTHENTICATE ->
                UserOperationHelper.processAuthenticate((AuthenticateRequest) operationRequest, clientId);
            case CREATE_USER -> UserOperationHelper.processCreateUser((CreateUserRequest) operationRequest);
            case DELETE_USER -> UserOperationHelper.processDeleteUser((DeleteUserRequest) operationRequest, restamped);
            case CHANGE_PERMISSIONS ->
                UserOperationHelper.processChangePermissions((ChangePermissionsRequest) operationRequest, restamped);
            case SET_PROJECT_OWNERS -> processSetProjectOwners((SetProjectOwnersRequest) operationRequest, restamped);
            case LIST_USERS -> processListUsers((ListUsersRequest) operationRequest);
            case SET_PASSWORD ->
                UserOperationHelper.processSetPassword((SetPasswordRequest) operationRequest, clientId);
            case GET_PROJECT_STATS -> ProjectStatsHelper.processGetProjectStats(actingUser);
            case LISTEN -> processListenOperation((ListenRequest) operationRequest, clientId);
            case STOP_LISTEN -> processStopListenOperation((StopListenRequest) operationRequest, clientId);
            case START_TRANSACTION ->
                TransactionOperationHelper.start(clientId, UUID.randomUUID(), operationRequest.getTriggerDepth());
            case COMMIT_TRANSACTION -> TransactionOperationHelper.commit(clientId);
            case ROLLBACK_TRANSACTION -> TransactionOperationHelper.rollback(clientId);
            case RESOLVE_TRANSACTION -> processResolveTransaction((ResolveTransactionRequest) operationRequest);
            case LIST_TRANSACTIONS -> processListTransactions();
            case RUN_SCRIPT -> processRunScriptOperation((RunScriptRequest) operationRequest, actingUser, clientId);
            case SAVE_PROCEDURE -> processSaveProcedure((SaveProcedureRequest) operationRequest, actingUser);
            case DELETE_PROCEDURE -> processDeleteProcedure((DeleteProcedureRequest) operationRequest, actingUser);
            case LIST_PROCEDURES -> processListProcedures((ListProceduresRequest) operationRequest, actingUser);
            case CALL_PROCEDURE -> processCallProcedure((CallProcedureRequest) operationRequest, actingUser, clientId);
            case SAVE_TRIGGER -> processSaveTrigger((SaveTriggerRequest) operationRequest, actingUser);
            case DELETE_TRIGGER -> processDeleteTrigger((DeleteTriggerRequest) operationRequest, actingUser);
            case LIST_TRIGGERS -> processListTriggers((ListTriggersRequest) operationRequest);
            case TEST_TRIGGER -> processTestTrigger((TestTriggerRequest) operationRequest, actingUser);
            case SAVE_SCHEDULE -> processSaveSchedule((SaveScheduleRequest) operationRequest, actingUser);
            case DELETE_SCHEDULE -> processDeleteSchedule((DeleteScheduleRequest) operationRequest, actingUser);
            case LIST_SCHEDULES -> processListSchedules((ListSchedulesRequest) operationRequest);
            case SAVE_ENTRYPOINT, DELETE_ENTRYPOINT, LIST_ENTRYPOINTS ->
                EntrypointOperationHelper.process(operationRequest, actingUser);
            case LIST_SCRIPTS -> processListScripts();
            case CANCEL_SCRIPT -> processCancelScript((CancelScriptRequest) operationRequest);
            case LIST_TRIGGER_RUNS -> processListTriggerRuns((ListTriggerRunsRequest) operationRequest);
            case RESOLVE_TRIGGER_RUN -> processResolveTriggerRun((ResolveTriggerRunRequest) operationRequest);
            case START_SNAPSHOT ->
                SnapshotOperationHelper.start((StartSnapshotRequest) operationRequest, clientId, actingUser);
            case READ_SNAPSHOT -> SnapshotOperationHelper.read((ReadSnapshotRequest) operationRequest, clientId);
            case RELEASE_SNAPSHOT ->
                SnapshotOperationHelper.release((ReleaseSnapshotRequest) operationRequest, clientId);
            case ARM_FAULT -> FaultOperationHelper.arm((ArmFaultRequest) operationRequest);
            case AWAIT_FAULT -> FaultOperationHelper.await((AwaitFaultRequest) operationRequest);
            case RELEASE_FAULT -> FaultOperationHelper.release((ReleaseFaultRequest) operationRequest);
        };
        return ClusterAdminHelper.afterAdminOp(operationRequest, response, restamped);
    }

    private OperationResponse processServerInfo(ServerInfoRequest request, String actingUser) {
        return ServerInfoHelper.process(request, actingUser);
    }

    private OperationResponse processCreateIndex(CreateIndexRequest createIndexRequest) {
        return IndexOperationHelper.processCreateIndex(createIndexRequest);
    }

    private OperationResponse processDropIndex(DropIndexRequest dropIndexRequest) {
        return IndexOperationHelper.processDropIndex(dropIndexRequest);
    }

    private OperationResponse processReindex(ReindexRequest request) {
        return IndexOperationHelper.processReindex(request);
    }

    private OperationResponse processFindByIdOperation(FindByIdRequest findbyIdRequest, Transaction activeTransaction) {
        return ReadPathHelper.processFindByIdOperation(findbyIdRequest, activeTransaction);
    }

    private OperationResponse processAggregateOperation(AggregateRequest aggregateRequest,
            Transaction activeTransaction) {
        return ReadPathHelper.processAggregateOperation(aggregateRequest, activeTransaction);
    }

    private OperationResponse processResolveTransaction(ResolveTransactionRequest request) {
        return RunDirectoryHelper.processResolveTransaction(request);
    }

    private OperationResponse processListScripts() {
        return RunDirectoryHelper.processListScripts();
    }

    private OperationResponse processCancelScript(CancelScriptRequest request) {
        return RunDirectoryHelper.processCancelScript(request);
    }

    private OperationResponse processListTriggerRuns(ListTriggerRunsRequest request) {
        return RunDirectoryHelper.processListTriggerRuns(request);
    }

    private OperationResponse processResolveTriggerRun(ResolveTriggerRunRequest request) {
        return RunDirectoryHelper.processResolveTriggerRun(request);
    }

    private OperationResponse processListTransactions() {
        return RunDirectoryHelper.processListTransactions();
    }

    private OperationResponse processCreateProjectOperation(CreateProjectRequest createProjectRequest, UUID clientId) {
        return ProjectOperationHelper.processCreateProjectOperation(createProjectRequest, clientId);
    }

    private OperationResponse processSetProjectOwners(SetProjectOwnersRequest request, RestampedRecords restamped) {
        final var response = ProjectOperationHelper.processSetProjectOwners(request);
        if (response.getStatus() == OperationStatus.OK) {
            DefinerRevocation.forProject(request.getProjectName(), restamped);
        }
        return response;
    }

    private OperationResponse processDropProjectOperation(DropProjectRequest dropProjectRequest,
            RestampedRecords restamped) {
        return ProjectOperationHelper.processDropProjectOperation(dropProjectRequest, restamped);
    }

    private OperationResponse processListProjectsOperation(String actingUser) {
        return ProjectOperationHelper.processListProjectsOperation(actingUser);
    }

    private OperationResponse processCreateCollectionOperation(CreateCollectionRequest createCollectionRequest) {
        return CollectionOperationHelper.processCreateCollectionOperation(createCollectionRequest);
    }

    private OperationResponse processDropCollectionOperation(DropCollectionRequest dropCollectionRequest,
            RestampedRecords restamped) {
        return CollectionOperationHelper.processDropCollectionOperation(dropCollectionRequest, restamped);
    }

    private OperationResponse processListCollectionsOperation(ListCollectionsRequest request) {
        return CollectionOperationHelper.processListCollectionsOperation(request);
    }

    private OperationResponse processListenOperation(ListenRequest listenRequest, UUID clientId) {
        return ListenOperationHelper.processListenOperation(listenRequest, clientId);
    }

    private OperationResponse processStopListenOperation(StopListenRequest request, UUID clientId) {
        return ListenOperationHelper.processStopListenOperation(request, clientId);
    }

    private OperationResponse processRunScriptOperation(RunScriptRequest request, String actingUser, UUID clientId) {
        return ScriptOperationHelper.execute(request, actingUser, clientId);
    }

    private OperationResponse processSaveProcedure(SaveProcedureRequest request, String actingUser) {
        return OperationResponse.respondOrError(OperationType.SAVE_PROCEDURE, ErrorCode.ERROR_SAVING_PROCEDURE,
                () -> ProcedureOperationHelper.executeSave(request, actingUser));
    }

    private OperationResponse processDeleteProcedure(DeleteProcedureRequest request, String actingUser) {
        return OperationResponse.respondOrError(OperationType.DELETE_PROCEDURE, ErrorCode.ERROR_DELETING_PROCEDURE,
                () -> ProcedureOperationHelper.executeDelete(request, actingUser));
    }

    private OperationResponse processListProcedures(ListProceduresRequest request, String actingUser) {
        return ProcedureOperationHelper.executeList(request, actingUser);
    }

    private OperationResponse processCallProcedure(CallProcedureRequest request, String actingUser, UUID clientId) {
        return ProcedureCallHelper.execute(request, actingUser, clientId);
    }

    private OperationResponse processSaveTrigger(SaveTriggerRequest request, String actingUser) {
        final var projectName = request.getProjectName();
        final var collName = request.getCollectionName();
        return OperationLocks.withCollectionLock(projectName, collName, OperationType.SAVE_TRIGGER,
                ErrorCode.ERROR_SAVING_TRIGGER, () -> TriggerOperationHelper.executeSave(request, actingUser));
    }

    private OperationResponse processDeleteTrigger(DeleteTriggerRequest request, String actingUser) {
        final var projectName = request.getProjectName();
        final var collName = request.getCollectionName();
        return OperationLocks.withCollectionLock(projectName, collName, OperationType.DELETE_TRIGGER,
                ErrorCode.ERROR_DELETING_TRIGGER, () -> TriggerOperationHelper.executeDelete(request, actingUser));
    }

    private OperationResponse processListTriggers(ListTriggersRequest request) {
        return TriggerOperationHelper.executeList(request);
    }

    // No lock: the hook runs against the caller's own document and touches nothing on disk.
    private OperationResponse processTestTrigger(TestTriggerRequest request, String actingUser) {
        return TriggerOperationHelper.executeTest(request, actingUser);
    }

    private OperationResponse processSaveSchedule(SaveScheduleRequest request, String actingUser) {
        return OperationResponse.respondOrError(OperationType.SAVE_SCHEDULE, ErrorCode.ERROR_SAVING_SCHEDULE,
                () -> ScheduleOperationHelper.executeSave(request, actingUser));
    }

    private OperationResponse processDeleteSchedule(DeleteScheduleRequest request, String actingUser) {
        return OperationResponse.respondOrError(OperationType.DELETE_SCHEDULE, ErrorCode.ERROR_DELETING_SCHEDULE,
                () -> ScheduleOperationHelper.executeDelete(request, actingUser));
    }

    private OperationResponse processListSchedules(ListSchedulesRequest request) {
        return ScheduleOperationHelper.executeList(request);
    }

    private OperationResponse processBulkSaveOperation(BulkSaveRequest bulkSaveRequest, Transaction activeTransaction,
            String actingUser) {
        final var projectName = bulkSaveRequest.getProjectName();
        final var collName = bulkSaveRequest.getCollectionName();
        if (activeTransaction != null) {
            return TransactionOperationHelper.bufferBulkSave(bulkSaveRequest, activeTransaction);
        }
        final var guardError = ClusterWriteHelper.guard(OperationType.BULK_SAVE, projectName, collName);
        if (guardError != null) {
            return guardError;
        }
        return OperationLocks.withCollectionLock(projectName, collName, OperationType.BULK_SAVE,
                ErrorCode.ERROR_BULK_SAVING, () -> {
                    final var ownershipError = ClusterWriteHelper.refusalUnderLock(OperationType.BULK_SAVE, projectName,
                            collName);
                    if (ownershipError != null) {
                        return ownershipError;
                    }
                    final var schemaError = SchemaValidationHelper.check(bulkSaveRequest);
                    if (schemaError != null) {
                        return schemaError;
                    }
                    final var hookError = BeforeHookHelper.beforeBulkSave(bulkSaveRequest, actingUser);
                    if (hookError != null) {
                        return hookError;
                    }
                    final var staged = TriggerHelper.stageBulkSave(bulkSaveRequest, actingUser);
                    try {
                        return ClusterWriteHelper.afterBulkSave(projectName, collName,
                                TriggerHelper.runStaged(staged, projectName, collName, actingUser,
                                        bulkSaveRequest.getTriggerDepth(),
                                        () -> SaveOperationHelper.executeBulkSave(bulkSaveRequest)));
                    } catch (PartialBulkSaveException e) {
                        ClusterWriteHelper.afterBulkSave(projectName, collName, e.committed());
                        throw e;
                    }
                });
    }

    private OperationResponse processSaveOperation(SaveRequest saveRequest, Transaction activeTransaction,
            String actingUser) {
        final var projectName = saveRequest.getProjectName();
        final var collName = saveRequest.getCollectionName();
        if (activeTransaction != null) {
            return TransactionOperationHelper.bufferSave(saveRequest, activeTransaction);
        }
        final var guardError = ClusterWriteHelper.guard(OperationType.SAVE, projectName, collName);
        if (guardError != null) {
            return guardError;
        }
        if (saveRequest.getCondition() != null) {
            return processSaveWhere(saveRequest, actingUser);
        }
        return OperationLocks.withGroupedCollectionLock(projectName, collName, OperationType.SAVE,
                ErrorCode.ERROR_SAVING, GroupCommit.forSave(saveRequest), effects -> {
                    final var ownershipError = ClusterWriteHelper.refusalUnderLock(OperationType.SAVE, projectName,
                            collName);
                    if (ownershipError != null) {
                        return GroupCommit.answered(ownershipError);
                    }
                    final var existsError = InsertOnlyGuard.check(saveRequest);
                    if (existsError != null) {
                        return GroupCommit.answered(existsError);
                    }
                    final var schemaError = SchemaValidationHelper.check(saveRequest);
                    if (schemaError != null) {
                        return GroupCommit.answered(schemaError);
                    }
                    final var hookError = BeforeHookHelper.beforeSave(saveRequest, actingUser);
                    if (hookError != null) {
                        return GroupCommit.answered(hookError);
                    }
                    final var staged = TriggerHelper.stageSave(saveRequest, actingUser, effects);
                    return () -> ClusterWriteHelper.afterSave(projectName, collName,
                            TriggerHelper.runStaged(staged, projectName, collName, actingUser,
                                    saveRequest.getTriggerDepth(), effects,
                                    () -> SaveOperationHelper.executeSave(saveRequest)),
                            effects);
                });
    }

    private OperationResponse processSaveWhere(SaveRequest request, String actingUser) {
        final var projectName = request.getProjectName();
        final var collName = request.getCollectionName();
        return OperationLocks.withCollectionLock(projectName, collName, OperationType.SAVE, ErrorCode.ERROR_SAVING,
                () -> {
                    final var update = ConditionalSave.prepare(request, actingUser);
                    if (update.answered() != null) {
                        return update.answered();
                    }
                    final var merged = update.merged();
                    final var staged = TriggerHelper.stageBulkSave(merged, actingUser);
                    return SaveWhereResponse.answering(ClusterWriteHelper.afterBulkSave(projectName, collName,
                            TriggerHelper.runStaged(staged, projectName, collName, actingUser,
                                    request.getTriggerDepth(), () -> SaveOperationHelper.executeBulkSave(merged))));
                });
    }

    private OperationResponse processDeleteOperation(DeleteRequest deleteRequest, Transaction activeTransaction,
            String actingUser) {
        final var projectName = deleteRequest.getProjectName();
        final var collName = deleteRequest.getCollectionName();
        if (activeTransaction != null) {
            return TransactionOperationHelper.bufferDelete(deleteRequest, activeTransaction);
        }
        final var guardError = ClusterWriteHelper.guard(OperationType.DELETE, projectName, collName);
        if (guardError != null) {
            return guardError;
        }
        if (deleteRequest.getCondition() != null) {
            return processDeleteWhere(deleteRequest, actingUser);
        }
        return OperationLocks.withGroupedCollectionLock(projectName, collName, OperationType.DELETE,
                ErrorCode.ERROR_DELETING, GroupCommit.forDelete(deleteRequest), effects -> {
                    final var ownershipError = ClusterWriteHelper.refusalUnderLock(OperationType.DELETE, projectName,
                            collName);
                    if (ownershipError != null) {
                        return GroupCommit.answered(ownershipError);
                    }
                    // Read before the delete: afterWrite needs the document that is about to disappear.
                    final var deleted = TriggerHelper.captureForDelete(projectName, collName, deleteRequest.get_id(),
                            deleteRequest.getTriggerDepth());
                    final var hookError = BeforeHookHelper.beforeDelete(deleteRequest, actingUser);
                    if (hookError != null) {
                        return GroupCommit.answered(hookError);
                    }
                    final var staged = TriggerHelper.stageDelete(deleteRequest, deleted, actingUser, effects);
                    return () -> {
                        final var reservation = TriggerHelper.discardingOnFailure(staged, effects,
                                () -> ClusterWriteHelper.reserveDelete(projectName, collName, deleteRequest.get_id()));
                        if (reservation.ownerLost()) {
                            effects.discard(staged);
                            return new OperationResponse(OperationType.DELETE, ErrorCode.NOT_COLLECTION_OWNER);
                        }
                        final var local = TriggerHelper.runStaged(staged, projectName, collName, actingUser,
                                deleteRequest.getTriggerDepth(), effects,
                                () -> ClusterWriteHelper.deleteOrRetract(deleteRequest, reservation.version()));
                        return ClusterWriteHelper.afterDelete(projectName, collName, deleteRequest.get_id(),
                                reservation.version(), local, effects);
                    };
                });
    }

    private OperationResponse processDeleteWhere(DeleteRequest request, String actingUser) {
        final var projectName = request.getProjectName();
        final var collName = request.getCollectionName();
        return OperationLocks.withCollectionLock(projectName, collName, OperationType.DELETE, ErrorCode.ERROR_DELETING,
                () -> {
                    final var deletion = ConditionalDelete.prepare(request, actingUser);
                    if (deletion.refusal() != null) {
                        return deletion.refusal();
                    }
                    final var staged = TriggerHelper.stageDeleteMany(request, deletion.captured(), actingUser);
                    return deletion.replicated(TriggerHelper.runStaged(staged, projectName, collName, actingUser,
                            request.getTriggerDepth(), deletion::execute));
                });
    }

    private OperationResponse processListUsers(ListUsersRequest request) {
        return UserOperationHelper.processListUsers(request);
    }

    private OperationResponse processSaveSchema(SaveSchemaRequest request) {
        return SchemaOperationHelper.processSaveSchema(request);
    }

    private OperationResponse processDeleteSchema(DeleteSchemaRequest request) {
        return SchemaOperationHelper.processDeleteSchema(request);
    }

    private OperationResponse processGetSchema(GetSchemaRequest request) {
        return SchemaOperationHelper.processGetSchema(request);
    }

}
