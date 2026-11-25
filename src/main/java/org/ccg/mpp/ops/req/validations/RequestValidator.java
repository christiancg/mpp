package org.ccg.mpp.ops.req.validations;

import org.ccg.mpp.ops.req.AggregateRequest;
import org.ccg.mpp.ops.req.ArmFaultRequest;
import org.ccg.mpp.ops.req.AuthenticateRequest;
import org.ccg.mpp.ops.req.AwaitFaultRequest;
import org.ccg.mpp.ops.req.BulkSaveRequest;
import org.ccg.mpp.ops.req.CallProcedureRequest;
import org.ccg.mpp.ops.req.CancelScriptRequest;
import org.ccg.mpp.ops.req.ChangePermissionsRequest;
import org.ccg.mpp.ops.req.CreateIndexRequest;
import org.ccg.mpp.ops.req.CreateUserRequest;
import org.ccg.mpp.ops.req.DeleteEntrypointRequest;
import org.ccg.mpp.ops.req.DeleteMiddlewareRequest;
import org.ccg.mpp.ops.req.DeleteProcedureRequest;
import org.ccg.mpp.ops.req.DeleteRequest;
import org.ccg.mpp.ops.req.DeleteScheduleRequest;
import org.ccg.mpp.ops.req.DeleteTriggerRequest;
import org.ccg.mpp.ops.req.DeleteUserRequest;
import org.ccg.mpp.ops.req.DropIndexRequest;
import org.ccg.mpp.ops.req.FindByIdRequest;
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
import org.ccg.mpp.ops.req.SaveEntrypointRequest;
import org.ccg.mpp.ops.req.SaveMiddlewareRequest;
import org.ccg.mpp.ops.req.SaveProcedureRequest;
import org.ccg.mpp.ops.req.SaveRequest;
import org.ccg.mpp.ops.req.SaveScheduleRequest;
import org.ccg.mpp.ops.req.SaveSchemaRequest;
import org.ccg.mpp.ops.req.SaveTriggerRequest;
import org.ccg.mpp.ops.req.SetPasswordRequest;
import org.ccg.mpp.ops.req.SetProjectOwnersRequest;
import org.ccg.mpp.ops.req.StartSnapshotRequest;
import org.ccg.mpp.ops.req.StopListenRequest;
import org.ccg.mpp.ops.req.TestTriggerRequest;

public class RequestValidator {

    public static ValidationResult validateSystemHistory(OperationRequest request) {
        return NameValidations.validateProjectAndColl(request, true, false);
    }

    public static ValidationResult validate(OperationRequest request) {
        return switch (request.getType()) {
            case SAVE -> DataRequestValidator.validateSave((SaveRequest) request);
            case BULK_SAVE -> DataRequestValidator.validateBulkSave((BulkSaveRequest) request);
            case FIND_BY_ID -> DataRequestValidator.validateFindById((FindByIdRequest) request);
            case DELETE -> DataRequestValidator.validateDelete((DeleteRequest) request);
            case AGGREGATE -> DataRequestValidator.validateAggregate((AggregateRequest) request);
            case CREATE_PROJECT, DROP_PROJECT -> NameValidations.validateProjectOnly(request, true);
            case LIST_PROJECTS, CLOSE_CONNECTION, GET_PROJECT_STATS, SERVER_INFO -> ValidationResult.ok();
            case CREATE_COLLECTION, DROP_COLLECTION, DELETE_SCHEMA, GET_SCHEMA ->
                NameValidations.validateProjectAndColl(request, true, true);
            case LIST_COLLECTIONS -> NameValidations.validateProjectOnly(request, false);
            case CREATE_INDEX -> DataRequestValidator.validateCreateIndex((CreateIndexRequest) request);
            case DROP_INDEX -> DataRequestValidator.validateDropIndex((DropIndexRequest) request);
            case REINDEX -> DataRequestValidator.validateReindex((ReindexRequest) request);
            case SAVE_SCHEMA -> DataRequestValidator.validateSaveSchema((SaveSchemaRequest) request);
            case AUTHENTICATE -> UserRequestValidator.validateAuthenticate((AuthenticateRequest) request);
            case CREATE_USER -> UserRequestValidator.validateCreateUser((CreateUserRequest) request);
            case DELETE_USER -> UserRequestValidator.validateDeleteUser((DeleteUserRequest) request);
            case CHANGE_PERMISSIONS ->
                UserRequestValidator.validateChangePermissions((ChangePermissionsRequest) request);
            case SET_PROJECT_OWNERS -> UserRequestValidator.validateSetProjectOwners((SetProjectOwnersRequest) request);
            case LIST_USERS -> UserRequestValidator.validateListUsers((ListUsersRequest) request);
            case SET_PASSWORD -> UserRequestValidator.validateSetPassword((SetPasswordRequest) request);
            case LISTEN -> ControlRequestValidator.validateListen((ListenRequest) request);
            case STOP_LISTEN -> ControlRequestValidator.validateStopListen((StopListenRequest) request);
            // Transaction control operations carry no db/coll/payload; authentication is still enforced
            // in MessageProcessor.
            case START_TRANSACTION, COMMIT_TRANSACTION, ROLLBACK_TRANSACTION, LIST_TRANSACTIONS, LIST_SCRIPTS ->
                ValidationResult.ok();
            case RESOLVE_TRANSACTION ->
                ControlRequestValidator.validateResolveTransaction((ResolveTransactionRequest) request);
            case RUN_SCRIPT -> ScriptRequestValidator.validateRunScript((RunScriptRequest) request);
            case SAVE_PROCEDURE -> ScriptRequestValidator.validateSaveProcedure((SaveProcedureRequest) request);
            case DELETE_PROCEDURE -> ScriptRequestValidator.validateDeleteProcedure((DeleteProcedureRequest) request);
            case LIST_PROCEDURES, LIST_SCHEDULES, LIST_ENTRYPOINTS, LIST_MIDDLEWARES ->
                NameValidations.validateProjectNameOnly(request.getProjectName());
            case CALL_PROCEDURE -> ScriptRequestValidator.validateCallProcedure((CallProcedureRequest) request);
            case SAVE_TRIGGER -> ScriptRequestValidator.validateSaveTrigger((SaveTriggerRequest) request);
            case DELETE_TRIGGER -> ScriptRequestValidator.validateDeleteTrigger((DeleteTriggerRequest) request);
            case LIST_TRIGGERS -> ScriptRequestValidator.validateListTriggers((ListTriggersRequest) request);
            case TEST_TRIGGER -> ScriptRequestValidator.validateTestTrigger((TestTriggerRequest) request);
            case SAVE_SCHEDULE -> ScriptRequestValidator.validateSaveSchedule((SaveScheduleRequest) request);
            case DELETE_SCHEDULE -> ScriptRequestValidator.validateDeleteSchedule((DeleteScheduleRequest) request);
            case SAVE_ENTRYPOINT -> ScriptRequestValidator.validateSaveEntrypoint((SaveEntrypointRequest) request);
            case DELETE_ENTRYPOINT ->
                ScriptRequestValidator.validateDeleteEntrypoint((DeleteEntrypointRequest) request);
            case SAVE_MIDDLEWARE -> ScriptRequestValidator.validateSaveMiddleware((SaveMiddlewareRequest) request);
            case DELETE_MIDDLEWARE ->
                ScriptRequestValidator.validateDeleteMiddleware((DeleteMiddlewareRequest) request);
            case CANCEL_SCRIPT -> ControlRequestValidator.validateCancelScript((CancelScriptRequest) request);
            case LIST_TRIGGER_RUNS -> ControlRequestValidator.validateListTriggerRuns((ListTriggerRunsRequest) request);
            case RESOLVE_TRIGGER_RUN ->
                ControlRequestValidator.validateResolveTriggerRun((ResolveTriggerRunRequest) request);
            case START_SNAPSHOT -> SnapshotRequestValidator.validateStart((StartSnapshotRequest) request);
            case READ_SNAPSHOT -> SnapshotRequestValidator.validateRead((ReadSnapshotRequest) request);
            case RELEASE_SNAPSHOT -> SnapshotRequestValidator.validateRelease((ReleaseSnapshotRequest) request);
            case WRITE_FILE, READ_FILE, STAT_FILE, LIST_FILES, DELETE_FILE, MOVE_FILE, MAKE_DIRECTORY ->
                FileRequestValidator.validate(request);
            case ARM_FAULT -> ControlRequestValidator.validateArmFault((ArmFaultRequest) request);
            case AWAIT_FAULT -> ControlRequestValidator.validateAwaitFault((AwaitFaultRequest) request);
            case RELEASE_FAULT -> ControlRequestValidator.validateReleaseFault((ReleaseFaultRequest) request);
        };
    }
}
