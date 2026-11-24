package org.ccg.mpp.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.ConfigKey;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.EntrypointDefinition;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.admin.AdminRecordKey;
import org.ccg.mpp.ops.admin.AdminStamp;
import org.ccg.mpp.ops.admin.StoredDefinitions;
import org.ccg.mpp.ops.entrypoint.EntrypointMethods;
import org.ccg.mpp.ops.entrypoint.EntrypointRouter;
import org.ccg.mpp.ops.entrypoint.InvalidRouteException;
import org.ccg.mpp.ops.entrypoint.RouteTemplate;
import org.ccg.mpp.ops.req.DeleteEntrypointRequest;
import org.ccg.mpp.ops.req.ListEntrypointsRequest;
import org.ccg.mpp.ops.req.OperationRequest;
import org.ccg.mpp.ops.req.SaveEntrypointRequest;
import org.ccg.mpp.ops.req.SaveProcedureRequest;
import org.ccg.mpp.ops.resp.ListEntrypointsResponse;
import org.ccg.mpp.ops.resp.OperationResponse;
import org.ccg.mpp.ops.resp.SaveEntrypointResponse;

public final class EntrypointOperationHelper {
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final EntrypointRouter router = IocContainer.get(EntrypointRouter.class);
    private static final Configuration configuration = Configuration.getInstance();
    private static final String URL_FIELD = "url";
    private static final String ROOT_PATH = "/";

    private record Route(String method, RouteTemplate template) {
    }

    private EntrypointOperationHelper() {
    }

    public static OperationResponse process(OperationRequest request, String actingUser) {
        return switch (request) {
            case SaveEntrypointRequest save -> OperationResponse.respondOrError(OperationType.SAVE_ENTRYPOINT,
                    ErrorCode.ERROR_SAVING_ENTRYPOINT, () -> executeSave(save, actingUser));
            case DeleteEntrypointRequest delete -> OperationResponse.respondOrError(OperationType.DELETE_ENTRYPOINT,
                    ErrorCode.ERROR_DELETING_ENTRYPOINT, () -> executeDelete(delete, actingUser));
            case ListEntrypointsRequest list -> executeList(list);
            default -> throw new IllegalArgumentException("Not an entrypoint operation: " + request.getType());
        };
    }

    public static OperationResponse executeSave(SaveEntrypointRequest request, String actingUser)
            throws IOException, InterruptedException {
        if (!configuration.isScriptsEnabled()) {
            return new OperationResponse(OperationType.SAVE_ENTRYPOINT, ErrorCode.SCRIPTS_DISABLED);
        }
        final var projectName = request.getProjectName();
        if (cache.getAdminProjectEntry(projectName) == null) {
            return projectNotFound(OperationType.SAVE_ENTRYPOINT, projectName);
        }
        final var method = EntrypointMethods.normalized(request.getMethod());
        if (method == null) {
            return invalid("method must be one of "
                    + String.join(", ", EntrypointMethods.ROUTABLE.stream().sorted().toList()));
        }
        final RouteTemplate template;
        try {
            template = RouteTemplate.parse(request.getPath());
        } catch (InvalidRouteException e) {
            return invalid(e.getMessage());
        }
        final var timeoutRefusal = timeoutRefusal(request.getTimeoutMs());
        if (timeoutRefusal != null) {
            return timeoutRefusal;
        }
        final var inline = request.getProcedure() == null
                ? null
                : request.getProcedure().toSaveProcedureRequest(projectName);
        final var inlineRefusal = BoundDefinitionSave.inlineRefusal(OperationType.SAVE_ENTRYPOINT, inline);
        if (inlineRefusal != null) {
            return inlineRefusal;
        }
        return new EntrypointSave(request, new Route(method, template), inline, actingUser).run(actingUser);
    }

    private static OperationResponse timeoutRefusal(long timeoutMs) {
        if (timeoutMs < 0) {
            return invalid("timeoutMs must not be negative");
        }
        final var maxTimeout = configuration.longValue(ConfigKey.ENTRYPOINT_MAX_TIMEOUT_MS);
        if (timeoutMs > maxTimeout) {
            return new OperationResponse(OperationType.SAVE_ENTRYPOINT,
                    ErrorCode.ENTRYPOINT_TIMEOUT_TOO_LARGE.getDefaultMessage() + " (" + maxTimeout + " ms)",
                    ErrorCode.ENTRYPOINT_TIMEOUT_TOO_LARGE);
        }
        return null;
    }

    private static final class EntrypointSave extends BoundDefinitionSave<EntrypointDefinition> {
        private static final Kind KIND = new Kind(OperationType.SAVE_ENTRYPOINT, ErrorCode.ERROR_SAVING_ENTRYPOINT,
                Globals.ENTRYPOINTS_FOLDER, ConfigKey.MAX_ENTRYPOINTS_PER_PROJECT, ErrorCode.TOO_MANY_ENTRYPOINTS);
        private final SaveEntrypointRequest request;
        private final Route route;
        private final String actingUser;

        EntrypointSave(SaveEntrypointRequest request, Route route, SaveProcedureRequest inline, String actingUser) {
            super(KIND, request, request.getName(), request.getProcedureName(), inline);
            this.request = request;
            this.route = route;
            this.actingUser = actingUser;
        }

        @Override
        protected EntrypointDefinition existing(String projectName, String name) {
            return cache.getEntrypoint(projectName, name);
        }

        @Override
        protected String definerOf(EntrypointDefinition definition) {
            return definition.getDefiner();
        }

        @Override
        protected List<String> names(String projectName) throws IOException {
            return fs.metadataFiles().listEntrypointNames(projectName);
        }

        @Override
        protected OperationResponse refusalAmong(List<String> names) {
            final var conflicting = sameRoute(request.getProjectName(), names, request.getName(), route);
            if (conflicting == null) {
                return null;
            }
            return new OperationResponse(OperationType.SAVE_ENTRYPOINT,
                    ErrorCode.ENTRYPOINT_ROUTE_CONFLICT.getDefaultMessage() + ": '" + conflicting.getName() + "' ("
                            + conflicting.getMethod() + " " + conflicting.getPath() + ")",
                    ErrorCode.ENTRYPOINT_ROUTE_CONFLICT);
        }

        @Override
        protected OperationResponse persist(EntrypointDefinition existing, long procedureVersion) throws IOException {
            final var projectName = request.getProjectName();
            final var definition = newDefinition(request, route, existing, actingUser);
            StoredDefinitions.writeEntrypoint(projectName, definition.getName(), definition.toJsonObject());
            cache.putEntrypoint(projectName, definition);
            router.reload(projectName);
            return new SaveEntrypointResponse("Entrypoint saved successfully", definition.getVersion(),
                    procedureVersion);
        }
    }

    private static EntrypointDefinition sameRoute(String projectName, List<String> names, String savedName,
            Route route) {
        for (final var name : names) {
            if (name.equals(savedName)) {
                continue;
            }
            final var other = cache.getEntrypoint(projectName, name);
            if (other != null && route.method().equals(EntrypointMethods.normalized(other.getMethod()))
                    && hasShapeOf(other.getPath(), route.template())) {
                return other;
            }
        }
        return null;
    }

    private static boolean hasShapeOf(String path, RouteTemplate template) {
        try {
            return RouteTemplate.parse(path).sameShape(template);
        } catch (InvalidRouteException unroutable) {
            return false;
        }
    }

    private static EntrypointDefinition newDefinition(SaveEntrypointRequest request, Route route,
            EntrypointDefinition existing, String actingUser) {
        final var version = existing == null ? 1L : existing.getVersion() + 1;
        final var updatedAt = System.currentTimeMillis();
        final var createdAt = existing == null ? updatedAt : existing.getCreatedAt();
        return new EntrypointDefinition(request.getName(), route.method(), route.template().normalized(),
                request.boundProcedureName(), request.getTimeoutMs(), request.isEnabled(), actingUser,
                request.getDescription(), version, createdAt, updatedAt, actingUser);
    }

    private static OperationResponse invalid(String reason) {
        return new OperationResponse(OperationType.SAVE_ENTRYPOINT,
                ErrorCode.INVALID_ENTRYPOINT.getDefaultMessage() + ": " + reason, ErrorCode.INVALID_ENTRYPOINT);
    }

    private static OperationResponse projectNotFound(OperationType type, String projectName) {
        return new OperationResponse(type, "Project '" + projectName + "' not found", ErrorCode.PROJECT_NOT_FOUND);
    }

    public static OperationResponse executeDelete(DeleteEntrypointRequest request, String actingUser)
            throws IOException, InterruptedException {
        final var projectName = request.getProjectName();
        if (cache.getAdminProjectEntry(projectName) == null) {
            return projectNotFound(OperationType.DELETE_ENTRYPOINT, projectName);
        }
        locks.lock(projectName, Globals.ENTRYPOINTS_FOLDER);
        try {
            final var existing = cache.getEntrypoint(projectName, request.getName());
            final var foreign = existing == null
                    ? null
                    : ProcedureBindingGuard.foreignDefinitionRefusal(OperationType.DELETE_ENTRYPOINT, projectName,
                            existing.getDefiner(), actingUser);
            if (foreign != null) {
                return foreign;
            }
            if (fs.metadataFiles().deleteEntrypoint(projectName, request.getName())) {
                AdminStamp.tombstone(AdminRecordKey.entrypoint(projectName, request.getName()));
            }
            cache.removeEntrypoint(projectName, request.getName());
            router.reload(projectName);
            return OperationResponse.ok(OperationType.DELETE_ENTRYPOINT, "Entrypoint deleted successfully");
        } finally {
            locks.release(projectName, Globals.ENTRYPOINTS_FOLDER);
        }
    }

    public static OperationResponse executeList(ListEntrypointsRequest request) {
        return OperationResponse.respondOrError(OperationType.LIST_ENTRYPOINTS, ErrorCode.ERROR_RETRIEVING,
                () -> listEntrypoints(request));
    }

    private static OperationResponse listEntrypoints(ListEntrypointsRequest request) throws IOException {
        final var projectName = request.getProjectName();
        if (cache.getAdminProjectEntry(projectName) == null) {
            return projectNotFound(OperationType.LIST_ENTRYPOINTS, projectName);
        }
        final var result = new ArrayList<JsonObject>();
        for (final var definition : entrypointsIn(projectName)) {
            final var json = definition.toJsonObject();
            json.addProperty(URL_FIELD, urlOf(projectName, definition.getPath()));
            result.add(json);
        }
        return new ListEntrypointsResponse("Ok", result);
    }

    static String urlOf(String projectName, String path) {
        return ROOT_PATH + projectName + (ROOT_PATH.equals(path) ? "" : path);
    }

    public static List<EntrypointDefinition> entrypointsIn(String projectName) throws IOException {
        final var result = new ArrayList<EntrypointDefinition>();
        for (final var name : fs.metadataFiles().listEntrypointNames(projectName)) {
            final var definition = cache.getEntrypoint(projectName, name);
            if (definition != null) {
                result.add(definition);
            }
        }
        return result;
    }

    public static String entrypointReferencing(String projectName, String procedureName) throws IOException {
        for (final var definition : entrypointsIn(projectName)) {
            if (procedureName.equals(definition.getProcedureName())) {
                return definition.getName();
            }
        }
        return null;
    }

    public static boolean disable(String projectName, EntrypointDefinition dispatched, String reason)
            throws IOException, InterruptedException {
        final var budget = OperationLocks.lockBudgetMillis(false);
        if (!locks.tryLockProjectShared(projectName, budget)) {
            return false;
        }
        try {
            if (!locks.tryLockWrite(projectName, Globals.ENTRYPOINTS_FOLDER, budget)) {
                return false;
            }
            try {
                final var existing = cache.getEntrypoint(projectName, dispatched.getName());
                if (existing == null || !existing.isEnabled() || existing.getVersion() != dispatched.getVersion()) {
                    return false;
                }
                final var disabled = existing.disabledCopy(reason, System.currentTimeMillis());
                StoredDefinitions.writeEntrypoint(projectName, disabled.getName(), disabled.toJsonObject());
                cache.putEntrypoint(projectName, disabled);
                router.reload(projectName);
                return true;
            } finally {
                locks.release(projectName, Globals.ENTRYPOINTS_FOLDER);
            }
        } finally {
            locks.releaseProjectShared(projectName);
        }
    }
}
