package org.ccg.mpp.ops;

import static org.ccg.mpp.simplejs.host.ProcedureModuleResolver.SPECIFIER_PREFIX;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.ConfigKey;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.ProcedureDefinition;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.admin.AdminRecordKey;
import org.ccg.mpp.ops.admin.AdminStamp;
import org.ccg.mpp.ops.admin.StoredDefinitions;
import org.ccg.mpp.ops.auth.AuthorizationChecker;
import org.ccg.mpp.ops.req.DeleteProcedureRequest;
import org.ccg.mpp.ops.req.ListProceduresRequest;
import org.ccg.mpp.ops.req.SaveProcedureRequest;
import org.ccg.mpp.ops.resp.ListProceduresResponse;
import org.ccg.mpp.ops.resp.OperationResponse;
import org.ccg.mpp.ops.resp.SaveProcedureResponse;
import org.ccg.mpp.simplejs.CompiledScript;
import org.ccg.mpp.simplejs.SimpleJs;
import org.ccg.mpp.simplejs.exceptions.SimpleJsRuntimeException;
import org.ccg.mpp.simplejs.exceptions.UnexpectedCharacterException;
import org.ccg.mpp.simplejs.exceptions.UnexpectedEndOfInputException;
import org.ccg.mpp.simplejs.exceptions.UnexpectedTokenException;
import org.ccg.mpp.simplejs.exceptions.UnterminatedCommentException;
import org.ccg.mpp.simplejs.exceptions.UnterminatedRegexException;
import org.ccg.mpp.simplejs.exceptions.UnterminatedStringException;
import org.ccg.mpp.simplejs.exceptions.UnterminatedTemplateException;

public final class ProcedureOperationHelper {
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final SimpleJs simpleJs = IocContainer.get(SimpleJs.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final CompiledProcedureCache compiledProcedures = IocContainer.get(CompiledProcedureCache.class);
    private static final Configuration configuration = Configuration.getInstance();

    private ProcedureOperationHelper() {
    }

    public record SaveProcedureOutcome(OperationResponse refusal, long version) {
    }

    public static OperationResponse executeSave(SaveProcedureRequest request, String actingUser)
            throws IOException, InterruptedException {
        if (!configuration.isScriptsEnabled()) {
            return new OperationResponse(OperationType.SAVE_PROCEDURE, ErrorCode.SCRIPTS_DISABLED);
        }
        final var projectName = request.getProjectName();
        if (cache.getAdminProjectEntry(projectName) == null) {
            return projectNotFound(projectName);
        }
        final var refusal = refusalBeforeSave(OperationType.SAVE_PROCEDURE, request);
        if (refusal != null) {
            return refusal;
        }
        return OperationLocks.withProjectShared(projectName, OperationType.SAVE_PROCEDURE,
                ErrorCode.ERROR_SAVING_PROCEDURE, () -> saveUnderProjectBarrier(request, actingUser));
    }

    static OperationResponse refusalBeforeSave(OperationType type, SaveProcedureRequest request) {
        final var source = request.getScript();
        if (source.getBytes(StandardCharsets.UTF_8).length > configuration.getScriptMaxSourceBytes()) {
            return new OperationResponse(type, ErrorCode.SCRIPT_TOO_LARGE);
        }
        final CompiledScript compiled;
        try {
            compiled = simpleJs.compile(source, false);
        } catch (SimpleJsRuntimeException | UnexpectedTokenException | UnexpectedEndOfInputException
                | UnexpectedCharacterException | UnterminatedStringException | UnterminatedTemplateException
                | UnterminatedCommentException | UnterminatedRegexException parseFailure) {
            return new OperationResponse(type,
                    ErrorCode.INVALID_PROCEDURE.getDefaultMessage() + ": " + parseFailure.getMessage(),
                    ErrorCode.INVALID_PROCEDURE);
        } catch (StackOverflowError tooDeep) {
            return new OperationResponse(type,
                    ErrorCode.INVALID_PROCEDURE.getDefaultMessage() + ": " + CompiledScriptCache.NESTING_TOO_DEEP,
                    ErrorCode.INVALID_PROCEDURE);
        }
        final var missingImport = firstUnresolvableImport(request, request.getProjectName(), compiled);
        if (missingImport != null) {
            return new OperationResponse(type,
                    ErrorCode.PROCEDURE_IMPORT_NOT_FOUND.getDefaultMessage() + ": '" + missingImport + "'",
                    ErrorCode.PROCEDURE_IMPORT_NOT_FOUND);
        }
        return null;
    }

    private static OperationResponse saveUnderProjectBarrier(SaveProcedureRequest request, String actingUser)
            throws IOException, InterruptedException {
        final var projectName = request.getProjectName();
        if (cache.getAdminProjectEntry(projectName) == null) {
            return projectNotFound(projectName);
        }
        locks.lock(projectName, Globals.PROCEDURES_FOLDER);
        try {
            final var outcome = saveHoldingLock(OperationType.SAVE_PROCEDURE, request, actingUser);
            return outcome.refusal() != null
                    ? outcome.refusal()
                    : new SaveProcedureResponse("Procedure saved successfully", outcome.version());
        } finally {
            locks.release(projectName, Globals.PROCEDURES_FOLDER);
        }
    }

    static SaveProcedureOutcome saveHoldingLock(OperationType type, SaveProcedureRequest request, String actingUser)
            throws IOException {
        final var projectName = request.getProjectName();
        final var refusal = ProcedureBindingGuard.refusal(type, projectName, request.getName(), actingUser);
        if (refusal != null) {
            return refused(refusal);
        }
        final var existing = cache.getProcedure(projectName, request.getName());
        if (request.conflictsWith(existing == null ? 0L : existing.getVersion())) {
            return refused(new OperationResponse(type, ErrorCode.PROCEDURE_VERSION_CONFLICT));
        }
        final var procedureNames = fs.metadataFiles().listProcedureNames(projectName);
        final var maxProcedures = configuration.intValue(ConfigKey.MAX_PROCEDURES_PER_PROJECT);
        if (existing == null && procedureNames.size() >= maxProcedures) {
            return refused(new OperationResponse(type,
                    ErrorCode.TOO_MANY_PROCEDURES.getDefaultMessage() + " (" + maxProcedures + ")",
                    ErrorCode.TOO_MANY_PROCEDURES));
        }
        final var colliding = OnDiskNameRegistry.collidingDefinition(procedureNames, request.getName());
        if (colliding != null) {
            return refused(new OperationResponse(type, ErrorCode.NAME_COLLIDES_ON_DISK, colliding));
        }
        final var definition = newDefinition(request, existing, actingUser);
        StoredDefinitions.writeProcedure(projectName, definition.getName(), definition.toJsonObject());
        cache.putProcedure(projectName, definition);
        compiledProcedures.invalidateProcedure(projectName, definition.getName());
        return new SaveProcedureOutcome(null, definition.getVersion());
    }

    private static SaveProcedureOutcome refused(OperationResponse refusal) {
        return new SaveProcedureOutcome(refusal, 0L);
    }

    private static OperationResponse projectNotFound(String projectName) {
        return new OperationResponse(OperationType.SAVE_PROCEDURE, "Project '" + projectName + "' not found",
                ErrorCode.PROJECT_NOT_FOUND);
    }

    private static String firstUnresolvableImport(SaveProcedureRequest request, String projectName,
            CompiledScript compiled) {
        for (final var specifier : simpleJs.moduleSpecifiers(compiled)) {
            if (!specifier.startsWith(SPECIFIER_PREFIX)) {
                continue;
            }
            final var name = specifier.substring(SPECIFIER_PREFIX.length());
            if (name.equals(request.getName())) {
                continue;
            }
            final var target = cache.getProcedure(projectName, name);
            if (target == null || !target.isEnabled()) {
                return specifier;
            }
        }
        return null;
    }

    private static ProcedureDefinition newDefinition(SaveProcedureRequest request, ProcedureDefinition existing,
            String actingUser) {
        final var version = existing == null ? 1L : existing.getVersion() + 1;
        final var updatedAt = System.currentTimeMillis();
        final var createdAt = existing == null ? updatedAt : existing.getCreatedAt();
        return new ProcedureDefinition(request.getName(), request.getScript(), version, request.getDescription(),
                request.isEnabled(), createdAt, updatedAt, actingUser);
    }

    public static OperationResponse executeDelete(DeleteProcedureRequest request, String actingUser)
            throws IOException, InterruptedException {
        final var projectName = request.getProjectName();
        if (cache.getAdminProjectEntry(projectName) == null) {
            return new OperationResponse(OperationType.DELETE_PROCEDURE, "Project '" + projectName + "' not found",
                    ErrorCode.PROJECT_NOT_FOUND);
        }
        locks.lock(projectName, Globals.PROCEDURES_FOLDER);
        try {
            final var refusal = ProcedureBindingGuard.refusal(OperationType.DELETE_PROCEDURE, projectName,
                    request.getName(), actingUser);
            if (refusal != null) {
                return refusal;
            }
            final var referencing = TriggerOperationHelper.triggerReferencing(projectName, request.getName());
            if (referencing != null) {
                return new OperationResponse(OperationType.DELETE_PROCEDURE,
                        "Procedure '" + request.getName() + "' is still referenced by trigger '" + referencing + "'",
                        ErrorCode.INVALID_TRIGGER);
            }
            final var scheduled = ScheduleOperationHelper.scheduleReferencing(projectName, request.getName());
            if (scheduled != null) {
                return new OperationResponse(OperationType.DELETE_PROCEDURE,
                        "Procedure '" + request.getName() + "' is still referenced by schedule '" + scheduled + "'",
                        ErrorCode.INVALID_SCHEDULE);
            }
            final var exposed = EntrypointOperationHelper.entrypointReferencing(projectName, request.getName());
            if (exposed != null) {
                return new OperationResponse(OperationType.DELETE_PROCEDURE,
                        "Procedure '" + request.getName() + "' is still referenced by entrypoint '" + exposed + "'",
                        ErrorCode.INVALID_ENTRYPOINT);
            }
            final var wrapping = MiddlewareOperationHelper.middlewareReferencing(projectName, request.getName());
            if (wrapping != null) {
                return new OperationResponse(OperationType.DELETE_PROCEDURE,
                        "Procedure '" + request.getName() + "' is still referenced by middleware '" + wrapping + "'",
                        ErrorCode.INVALID_MIDDLEWARE);
            }
            if (fs.metadataFiles().deleteProcedure(projectName, request.getName())) {
                AdminStamp.tombstone(AdminRecordKey.procedure(projectName, request.getName()));
            }
            cache.removeProcedure(projectName, request.getName());
            compiledProcedures.invalidateProcedure(projectName, request.getName());
            return OperationResponse.ok(OperationType.DELETE_PROCEDURE, "Procedure deleted successfully");
        } finally {
            locks.release(projectName, Globals.PROCEDURES_FOLDER);
        }
    }

    public static OperationResponse executeList(ListProceduresRequest request, String actingUser) {
        return OperationResponse.respondOrError(OperationType.LIST_PROCEDURES, ErrorCode.ERROR_RETRIEVING,
                () -> listProcedures(request, actingUser));
    }

    private static OperationResponse listProcedures(ListProceduresRequest request, String actingUser)
            throws IOException {
        final var projectName = request.getProjectName();
        if (cache.getAdminProjectEntry(projectName) == null) {
            return new OperationResponse(OperationType.LIST_PROCEDURES, "Project '" + projectName + "' not found",
                    ErrorCode.PROJECT_NOT_FOUND);
        }
        final var showSourceHash = actingUser == null
                || AuthorizationChecker.mayReadProcedureSource(cache.getAdminUserEntry(actingUser), projectName);
        final var result = new ArrayList<org.ccg.mpp.ejson.elements.JsonObject>();
        for (final var name : fs.metadataFiles().listProcedureNames(projectName)) {
            final var definition = cache.getProcedure(projectName, name);
            if (definition != null) {
                result.add(request.isIncludeSource()
                        ? definition.toJsonObject()
                        : definition.toSummaryJson(showSourceHash));
            }
        }
        return new ListProceduresResponse("Ok", result);
    }
}
