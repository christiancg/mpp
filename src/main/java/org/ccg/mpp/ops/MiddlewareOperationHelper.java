package org.ccg.mpp.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.ConfigKey;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.MiddlewareDefinition;
import org.ccg.mpp.data.MiddlewareMatch;
import org.ccg.mpp.data.MiddlewarePhase;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.admin.AdminRecordKey;
import org.ccg.mpp.ops.admin.AdminStamp;
import org.ccg.mpp.ops.admin.StoredDefinitions;
import org.ccg.mpp.ops.middleware.InvalidMiddlewareException;
import org.ccg.mpp.ops.middleware.MiddlewareChains;
import org.ccg.mpp.ops.middleware.MiddlewareMatchParser;
import org.ccg.mpp.ops.req.DeleteMiddlewareRequest;
import org.ccg.mpp.ops.req.ListMiddlewaresRequest;
import org.ccg.mpp.ops.req.OperationRequest;
import org.ccg.mpp.ops.req.SaveMiddlewareRequest;
import org.ccg.mpp.ops.req.SaveProcedureRequest;
import org.ccg.mpp.ops.resp.ListMiddlewaresResponse;
import org.ccg.mpp.ops.resp.OperationResponse;
import org.ccg.mpp.ops.resp.SaveMiddlewareResponse;

public final class MiddlewareOperationHelper {
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final MiddlewareChains chains = IocContainer.get(MiddlewareChains.class);
    private static final Configuration configuration = Configuration.getInstance();

    private record Shape(MiddlewarePhase phase, MiddlewareMatch match) {
    }

    private MiddlewareOperationHelper() {
    }

    public static OperationResponse process(OperationRequest request, String actingUser) {
        return switch (request) {
            case SaveMiddlewareRequest save -> OperationResponse.respondOrError(OperationType.SAVE_MIDDLEWARE,
                    ErrorCode.ERROR_SAVING_MIDDLEWARE, () -> executeSave(save, actingUser));
            case DeleteMiddlewareRequest delete -> OperationResponse.respondOrError(OperationType.DELETE_MIDDLEWARE,
                    ErrorCode.ERROR_DELETING_MIDDLEWARE, () -> executeDelete(delete, actingUser));
            case ListMiddlewaresRequest list -> executeList(list);
            default -> throw new IllegalArgumentException("Not a middleware operation: " + request.getType());
        };
    }

    public static OperationResponse executeSave(SaveMiddlewareRequest request, String actingUser) {
        if (!configuration.isScriptsEnabled()) {
            return new OperationResponse(OperationType.SAVE_MIDDLEWARE, ErrorCode.SCRIPTS_DISABLED);
        }
        final var projectName = request.getProjectName();
        if (cache.getAdminProjectEntry(projectName) == null) {
            return projectNotFound(OperationType.SAVE_MIDDLEWARE, projectName);
        }
        final Shape shape;
        try {
            shape = shapeOf(request);
        } catch (InvalidMiddlewareException e) {
            return invalid(e.getMessage());
        }
        final var inline = request.getProcedure() == null
                ? null
                : request.getProcedure().toSaveProcedureRequest(projectName);
        final var inlineRefusal = BoundDefinitionSave.inlineRefusal(OperationType.SAVE_MIDDLEWARE, inline);
        if (inlineRefusal != null) {
            return inlineRefusal;
        }
        return new MiddlewareSave(request, shape, inline, actingUser).run(actingUser);
    }

    private static Shape shapeOf(SaveMiddlewareRequest request) throws InvalidMiddlewareException {
        final var phase = request.getPhase();
        if (phase == null) {
            throw new InvalidMiddlewareException("phase must be INGRESS or EGRESS");
        }
        if (request.getTimeoutMs() < 0) {
            throw new InvalidMiddlewareException("timeoutMs must not be negative");
        }
        final var maxTimeout = configuration.longValue(ConfigKey.ENTRYPOINT_MAX_TIMEOUT_MS);
        if (request.getTimeoutMs() > maxTimeout) {
            throw new InvalidMiddlewareException(
                    "timeoutMs must not exceed entrypointMaxTimeoutMs (" + maxTimeout + " ms)");
        }
        final var match = MiddlewareMatchParser.parse(request.getMatch(),
                configuration.intValue(ConfigKey.MAX_ENTRYPOINTS_PER_PROJECT));
        return new Shape(phase, match);
    }

    private static final class MiddlewareSave extends BoundDefinitionSave<MiddlewareDefinition> {
        private static final Kind KIND = new Kind(OperationType.SAVE_MIDDLEWARE, ErrorCode.ERROR_SAVING_MIDDLEWARE,
                Globals.MIDDLEWARES_FOLDER, ConfigKey.MAX_MIDDLEWARES_PER_PROJECT, ErrorCode.TOO_MANY_MIDDLEWARES);
        private final SaveMiddlewareRequest request;
        private final Shape shape;
        private final String actingUser;

        MiddlewareSave(SaveMiddlewareRequest request, Shape shape, SaveProcedureRequest inline, String actingUser) {
            super(KIND, request, request.getName(), request.getProcedureName(), inline);
            this.request = request;
            this.shape = shape;
            this.actingUser = actingUser;
        }

        @Override
        protected MiddlewareDefinition existing(String projectName, String name) {
            return cache.getMiddleware(projectName, name);
        }

        @Override
        protected String definerOf(MiddlewareDefinition definition) {
            return definition.getDefiner();
        }

        @Override
        protected List<String> names(String projectName) throws IOException {
            return fs.metadataFiles().listMiddlewareNames(projectName);
        }

        @Override
        protected OperationResponse persist(MiddlewareDefinition existing, long procedureVersion) throws IOException {
            final var projectName = request.getProjectName();
            final var updatedAt = System.currentTimeMillis();
            final var definition = new MiddlewareDefinition(request.getName(), shape.phase(), request.getOrder(),
                    shape.match(), request.boundProcedureName(), request.getTimeoutMs(), request.isEnabled(),
                    actingUser, request.getDescription()).stamped(existing == null ? 1L : existing.getVersion() + 1,
                            existing == null ? updatedAt : existing.getCreatedAt(), updatedAt, actingUser);
            StoredDefinitions.writeMiddleware(projectName, definition.getName(), definition.toJsonObject());
            cache.putMiddleware(projectName, definition);
            chains.reload(projectName);
            return new SaveMiddlewareResponse("Middleware saved successfully", definition.getVersion(),
                    procedureVersion);
        }
    }

    private static OperationResponse invalid(String reason) {
        return new OperationResponse(OperationType.SAVE_MIDDLEWARE,
                ErrorCode.INVALID_MIDDLEWARE.getDefaultMessage() + ": " + reason, ErrorCode.INVALID_MIDDLEWARE);
    }

    private static OperationResponse projectNotFound(OperationType type, String projectName) {
        return new OperationResponse(type, "Project '" + projectName + "' not found", ErrorCode.PROJECT_NOT_FOUND);
    }

    public static OperationResponse executeDelete(DeleteMiddlewareRequest request, String actingUser)
            throws IOException, InterruptedException {
        final var projectName = request.getProjectName();
        if (cache.getAdminProjectEntry(projectName) == null) {
            return projectNotFound(OperationType.DELETE_MIDDLEWARE, projectName);
        }
        locks.lock(projectName, Globals.MIDDLEWARES_FOLDER);
        try {
            final var existing = cache.getMiddleware(projectName, request.getName());
            final var foreign = existing == null
                    ? null
                    : ProcedureBindingGuard.foreignDefinitionRefusal(OperationType.DELETE_MIDDLEWARE, projectName,
                            existing.getDefiner(), actingUser);
            if (foreign != null) {
                return foreign;
            }
            if (fs.metadataFiles().deleteMiddleware(projectName, request.getName())) {
                AdminStamp.tombstone(AdminRecordKey.middleware(projectName, request.getName()));
            }
            cache.removeMiddleware(projectName, request.getName());
            chains.reload(projectName);
            return OperationResponse.ok(OperationType.DELETE_MIDDLEWARE, "Middleware deleted successfully");
        } finally {
            locks.release(projectName, Globals.MIDDLEWARES_FOLDER);
        }
    }

    public static OperationResponse executeList(ListMiddlewaresRequest request) {
        return OperationResponse.respondOrError(OperationType.LIST_MIDDLEWARES, ErrorCode.ERROR_RETRIEVING,
                () -> listMiddlewares(request));
    }

    private static OperationResponse listMiddlewares(ListMiddlewaresRequest request) throws IOException {
        final var projectName = request.getProjectName();
        if (cache.getAdminProjectEntry(projectName) == null) {
            return projectNotFound(OperationType.LIST_MIDDLEWARES, projectName);
        }
        final var result = new ArrayList<JsonObject>();
        for (final var definition : middlewaresIn(projectName)) {
            result.add(definition.toJsonObject());
        }
        return new ListMiddlewaresResponse("Ok", result);
    }

    public static List<MiddlewareDefinition> middlewaresIn(String projectName) throws IOException {
        final var result = new ArrayList<MiddlewareDefinition>();
        for (final var name : fs.metadataFiles().listMiddlewareNames(projectName)) {
            final var definition = cache.getMiddleware(projectName, name);
            if (definition != null && definition.getPhase() != null) {
                result.add(definition);
            }
        }
        result.sort(MiddlewareChains.EXECUTION_ORDER);
        return result;
    }

    public static String middlewareReferencing(String projectName, String procedureName) throws IOException {
        for (final var definition : middlewaresIn(projectName)) {
            if (procedureName.equals(definition.getProcedureName())) {
                return definition.getName();
            }
        }
        return null;
    }

    public static boolean disable(String projectName, MiddlewareDefinition dispatched, String reason)
            throws IOException, InterruptedException {
        final var budget = OperationLocks.lockBudgetMillis(false);
        if (!locks.tryLockProjectShared(projectName, budget)) {
            return false;
        }
        try {
            if (!locks.tryLockWrite(projectName, Globals.MIDDLEWARES_FOLDER, budget)) {
                return false;
            }
            try {
                final var existing = cache.getMiddleware(projectName, dispatched.getName());
                if (existing == null || !existing.isEnabled() || existing.getVersion() != dispatched.getVersion()) {
                    return false;
                }
                final var disabled = existing.disabledCopy(reason, System.currentTimeMillis());
                StoredDefinitions.writeMiddleware(projectName, disabled.getName(), disabled.toJsonObject());
                cache.putMiddleware(projectName, disabled);
                chains.reload(projectName);
                return true;
            } finally {
                locks.release(projectName, Globals.MIDDLEWARES_FOLDER);
            }
        } finally {
            locks.releaseProjectShared(projectName);
        }
    }
}
