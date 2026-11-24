package org.ccg.mpp.ops.req.validations;

import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.req.CallProcedureRequest;
import org.ccg.mpp.ops.req.DeleteEntrypointRequest;
import org.ccg.mpp.ops.req.DeleteMiddlewareRequest;
import org.ccg.mpp.ops.req.DeleteProcedureRequest;
import org.ccg.mpp.ops.req.DeleteScheduleRequest;
import org.ccg.mpp.ops.req.DeleteTriggerRequest;
import org.ccg.mpp.ops.req.InlineProcedure;
import org.ccg.mpp.ops.req.ListTriggersRequest;
import org.ccg.mpp.ops.req.RunScriptRequest;
import org.ccg.mpp.ops.req.SaveEntrypointRequest;
import org.ccg.mpp.ops.req.SaveMiddlewareRequest;
import org.ccg.mpp.ops.req.SaveProcedureRequest;
import org.ccg.mpp.ops.req.SaveScheduleRequest;
import org.ccg.mpp.ops.req.SaveTriggerRequest;
import org.ccg.mpp.ops.req.TestTriggerRequest;

public final class ScriptRequestValidator {
    private ScriptRequestValidator() {
    }

    static ValidationResult validateRunScript(RunScriptRequest request) {
        final var projectResult = NameValidations.validateProjectName(request.getProjectName(), true);
        if (!projectResult.isValid()) {
            return projectResult;
        }
        final var script = request.getScript();
        if (script == null || script.isBlank()) {
            return ValidationResult.fail("RUN_SCRIPT request requires a script");
        }
        return ValidationResult.ok();
    }

    static ValidationResult validateSaveProcedure(SaveProcedureRequest request) {
        final var projectResult = NameValidations.validateProjectName(request.getProjectName(), true);
        if (!projectResult.isValid()) {
            return projectResult;
        }
        final var nameResult = NameValidations.validateProcedureName(request.getName());
        if (!nameResult.isValid()) {
            return nameResult;
        }
        final var script = request.getScript();
        if (script == null || script.isBlank()) {
            return ValidationResult.fail("SAVE_PROCEDURE request requires a script");
        }
        return ValidationResult.ok();
    }

    static ValidationResult validateDeleteProcedure(DeleteProcedureRequest request) {
        final var projectResult = NameValidations.validateProjectName(request.getProjectName(), true);
        if (!projectResult.isValid()) {
            return projectResult;
        }
        return NameValidations.validateProcedureName(request.getName());
    }

    static ValidationResult validateCallProcedure(CallProcedureRequest request) {
        final var projectResult = NameValidations.validateProjectName(request.getProjectName(), true);
        if (!projectResult.isValid()) {
            return projectResult;
        }
        return NameValidations.validateProcedureName(request.getProcedureName());
    }

    static ValidationResult validateSaveTrigger(SaveTriggerRequest request) {
        final var namesResult = NameValidations.validateProjectAndColl(request, true, true);
        if (!namesResult.isValid()) {
            return namesResult;
        }
        final var nameResult = NameValidations.validateProcedureName(request.getName());
        if (!nameResult.isValid()) {
            return nameResult;
        }
        return NameValidations.validateProcedureName(request.getProcedureName());
    }

    static ValidationResult validateDeleteTrigger(DeleteTriggerRequest request) {
        final var namesResult = NameValidations.validateProjectAndColl(request, true);
        if (!namesResult.isValid()) {
            return namesResult;
        }
        return NameValidations.validateProcedureName(request.getName());
    }

    static ValidationResult validateTestTrigger(TestTriggerRequest request) {
        final var namesResult = NameValidations.validateProjectAndColl(request, true);
        if (!namesResult.isValid()) {
            return namesResult;
        }
        final var nameResult = NameValidations.validateProcedureName(request.getName());
        if (!nameResult.isValid()) {
            return nameResult;
        }
        if (request.getDocument() == null) {
            return ValidationResult.fail("A document is required");
        }
        return ValidationResult.ok();
    }

    static ValidationResult validateListTriggers(ListTriggersRequest request) {
        final var projectResult = NameValidations.validateProjectName(request.getProjectName(), true);
        if (!projectResult.isValid()) {
            return projectResult;
        }
        final var collName = request.getCollectionName();
        if (collName == null || collName.isBlank()) {
            return ValidationResult.ok();
        }
        return NameValidations.validateCollectionName(collName);
    }

    static ValidationResult validateSaveSchedule(SaveScheduleRequest request) {
        final var projectResult = NameValidations.validateProjectName(request.getProjectName(), true);
        if (!projectResult.isValid()) {
            return projectResult;
        }
        final var nameResult = NameValidations.validateProcedureName(request.getName());
        if (!nameResult.isValid()) {
            return nameResult;
        }
        return NameValidations.validateProcedureName(request.getProcedureName());
    }

    static ValidationResult validateDeleteSchedule(DeleteScheduleRequest request) {
        final var projectResult = NameValidations.validateProjectName(request.getProjectName(), true);
        if (!projectResult.isValid()) {
            return projectResult;
        }
        return NameValidations.validateProcedureName(request.getName());
    }

    static ValidationResult validateSaveEntrypoint(SaveEntrypointRequest request) {
        final var projectResult = NameValidations.validateProjectName(request.getProjectName(), true);
        if (!projectResult.isValid()) {
            return projectResult;
        }
        final var nameResult = NameValidations.validateProcedureName(request.getName());
        if (!nameResult.isValid()) {
            return nameResult;
        }
        return validateBoundProcedure(ErrorCode.INVALID_ENTRYPOINT, OperationType.SAVE_ENTRYPOINT,
                request.getProcedureName(), request.getProcedure());
    }

    static ValidationResult validateDeleteEntrypoint(DeleteEntrypointRequest request) {
        return validateProjectAndName(request.getProjectName(), request.getName());
    }

    static ValidationResult validateSaveMiddleware(SaveMiddlewareRequest request) {
        final var projectResult = NameValidations.validateProjectName(request.getProjectName(), true);
        if (!projectResult.isValid()) {
            return projectResult;
        }
        final var nameResult = NameValidations.validateProcedureName(request.getName());
        if (!nameResult.isValid()) {
            return nameResult;
        }
        if (request.getPhase() == null) {
            return ValidationResult.fail(ErrorCode.INVALID_MIDDLEWARE,
                    ErrorCode.INVALID_MIDDLEWARE.getDefaultMessage() + ": 'phase' must be INGRESS or EGRESS");
        }
        return validateBoundProcedure(ErrorCode.INVALID_MIDDLEWARE, OperationType.SAVE_MIDDLEWARE,
                request.getProcedureName(), request.getProcedure());
    }

    static ValidationResult validateDeleteMiddleware(DeleteMiddlewareRequest request) {
        return validateProjectAndName(request.getProjectName(), request.getName());
    }

    private static ValidationResult validateProjectAndName(String projectName, String name) {
        final var projectResult = NameValidations.validateProjectName(projectName, true);
        if (!projectResult.isValid()) {
            return projectResult;
        }
        return NameValidations.validateProcedureName(name);
    }

    private static ValidationResult validateBoundProcedure(ErrorCode invalid, OperationType type, String procedureName,
            InlineProcedure inline) {
        if ((inline == null) == (procedureName == null)) {
            return ValidationResult.fail(invalid,
                    invalid.getDefaultMessage() + ": exactly one of 'procedureName' and 'procedure' is required");
        }
        if (inline == null) {
            return NameValidations.validateProcedureName(procedureName);
        }
        final var inlineNameResult = NameValidations.validateProcedureName(inline.getName());
        if (!inlineNameResult.isValid()) {
            return inlineNameResult;
        }
        if (inline.getScript() == null || inline.getScript().isBlank()) {
            return ValidationResult.fail(type + " request requires a script in 'procedure'");
        }
        return ValidationResult.ok();
    }
}
