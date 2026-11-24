package org.ccg.mpp.ops;

import static org.ccg.mpp.simplejs.host.ScriptErrorNames.ARRAY_TOO_LARGE;
import static org.ccg.mpp.simplejs.host.ScriptErrorNames.CANCELLED;
import static org.ccg.mpp.simplejs.host.ScriptErrorNames.EXHAUSTED_MEMORY_MESSAGE;
import static org.ccg.mpp.simplejs.host.ScriptErrorNames.LIMIT;
import static org.ccg.mpp.simplejs.host.ScriptErrorNames.MEMORY;
import static org.ccg.mpp.simplejs.host.ScriptErrorNames.PENDING_RESULT;
import static org.ccg.mpp.simplejs.host.ScriptErrorNames.RESULT_TOO_LARGE;
import static org.ccg.mpp.simplejs.host.ScriptErrorNames.TIMEOUT;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.log.Logger;
import org.ccg.mpp.ops.auth.AuthorizationChecker;
import org.ccg.mpp.ops.req.RunScriptRequest;
import org.ccg.mpp.ops.resp.OperationResponse;
import org.ccg.mpp.ops.resp.RunScriptResponse;
import org.ccg.mpp.simplejs.CompiledScript;
import org.ccg.mpp.simplejs.SimpleJs;
import org.ccg.mpp.simplejs.host.EnforcingProjectAccess;
import org.ccg.mpp.simplejs.host.HostBindings;
import org.ccg.mpp.simplejs.host.ProjectHostBindings;
import org.ccg.mpp.simplejs.host.ResourceLimits;
import org.ccg.mpp.simplejs.host.ScriptResult;

public final class ScriptOperationHelper {
    private static final SimpleJs simpleJs = IocContainer.get(SimpleJs.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ScriptRunRegistry registry = IocContainer.get(ScriptRunRegistry.class);
    private static final ScriptAdmission admission = IocContainer.get(ScriptAdmission.class);
    private static final CompiledScriptCache compiledScripts = IocContainer.get(CompiledScriptCache.class);
    private static final Configuration configuration = Configuration.getInstance();
    private static final Logger logger = Logger.logFor(ScriptOperationHelper.class);
    private static final Set<ScriptRunKind> SELF_RECORDED_KINDS = EnumSet.of(ScriptRunKind.TRIGGER,
            ScriptRunKind.SCHEDULE, ScriptRunKind.ENTRYPOINT, ScriptRunKind.MIDDLEWARE);

    private ScriptOperationHelper() {
    }

    public static OperationResponse execute(RunScriptRequest request, String username, UUID clientId) {
        if (!configuration.isScriptsEnabled()) {
            return new OperationResponse(OperationType.RUN_SCRIPT, ErrorCode.SCRIPTS_DISABLED);
        }
        final var projectName = request.getProjectName();
        if (cache.getAdminProjectEntry(projectName) == null) {
            return new OperationResponse(OperationType.RUN_SCRIPT, "Project '" + projectName + "' not found",
                    ErrorCode.PROJECT_NOT_FOUND);
        }
        final var source = request.getScript();
        if (source.getBytes(StandardCharsets.UTF_8).length > configuration.getScriptMaxSourceBytes()) {
            return new OperationResponse(OperationType.RUN_SCRIPT, ErrorCode.SCRIPT_TOO_LARGE);
        }
        return switch (admit(username, projectName)) {
            case ScriptAdmission.Refusal refusal -> concurrencyRefusal(OperationType.RUN_SCRIPT, refusal.scope());
            case ScriptAdmission.Permit permit -> runAdmitted(permit, request, username, clientId);
        };
    }

    private static OperationResponse runAdmitted(ScriptAdmission.Permit permit, RunScriptRequest request,
            String username, UUID clientId) {
        final var projectName = request.getProjectName();
        final var source = request.getScript();
        try (permit) {
            final var run = registry.register(ScriptRunKind.RUN_SCRIPT, projectName, null, username, clientId);
            try {
                final var compilation = compiledScripts.get(source);
                final var project = new EnforcingProjectAccess(username, clientId, projectName);
                final var host = hostFor(request.getArgs(), project, ProjectHostBindings.limitsFromConfiguration(),
                        null, run);
                final var start = System.currentTimeMillis();
                final var raw = compilation.failure() == null
                        ? simpleJs.run(compilation.compiled(), host)
                        : simpleJs.failure(compilation.failure());
                final var elapsed = System.currentTimeMillis() - start;
                final var result = raw
                        .withMetrics(raw.getMetrics().withHostCounters(project.operationCount(), elapsed));
                logRun("RUN_SCRIPT user=" + username + " project=" + projectName, run.runId(), elapsed, result);
                ScriptRunHistory.record(historyOf(run.runId(), ScriptRunKind.RUN_SCRIPT, projectName, null, username,
                        start, elapsed, result));
                return toRunScriptResponse(result, run.runId());
            } finally {
                registry.unregister(run.runId());
            }
        }
    }

    static ScriptAdmission.Admission admit(String username, String projectName) {
        final var privileged = username == null
                || AuthorizationChecker.ownsOrAdministers(cache.getAdminUserEntry(username), projectName);
        return admission.acquire(username, projectName, privileged);
    }

    static OperationResponse concurrencyRefusal(OperationType type, String scope) {
        return new OperationResponse(type,
                ErrorCode.SCRIPT_CONCURRENCY_LIMIT.getDefaultMessage() + " (" + scope + " limit)",
                ErrorCode.SCRIPT_CONCURRENCY_LIMIT);
    }

    public record RunOutcome(String runId, ScriptResult result) {
    }

    static RunOutcome runProcedure(CompiledScript compiled, JsonObject args, String projectName, String username,
            UUID clientId, String logPrefix, String name) {
        return runCompiled(compiled, args, projectName, username, clientId, logPrefix, ScriptRunKind.CALL_PROCEDURE,
                name, ProjectHostBindings.limitsFromConfiguration(), null);
    }

    static RunOutcome runCompiled(CompiledScript compiled, JsonObject args, String projectName, String username,
            UUID clientId, String logPrefix, ScriptRunKind kind, String name, ResourceLimits limits,
            Consumer<String> console) {
        return runCompiled(compiled, args, projectName, username, clientId, logPrefix, kind, name, limits, console,
                host -> host);
    }

    static RunOutcome runCompiled(CompiledScript compiled, JsonObject args, String projectName, String username,
            UUID clientId, String logPrefix, ScriptRunKind kind, String name, ResourceLimits limits,
            Consumer<String> console, Function<ProjectHostBindings, HostBindings> hostDecorator) {
        final var run = registry.register(kind, projectName, name, username, clientId);
        try {
            final var project = new EnforcingProjectAccess(username, clientId, projectName);
            final var host = hostDecorator.apply(hostFor(args, project, limits, console, run));
            final var start = System.currentTimeMillis();
            final var raw = simpleJs.run(compiled, host);
            final var elapsed = System.currentTimeMillis() - start;
            final var result = raw.withMetrics(raw.getMetrics().withHostCounters(project.operationCount(), elapsed));
            logRun(logPrefix, run.runId(), elapsed, result);
            if (!SELF_RECORDED_KINDS.contains(kind)) {
                ScriptRunHistory
                        .record(historyOf(run.runId(), kind, projectName, name, username, start, elapsed, result));
            }
            return new RunOutcome(run.runId(), result);
        } finally {
            registry.unregister(run.runId());
        }
    }

    private static ProjectHostBindings hostFor(JsonObject args, EnforcingProjectAccess project, ResourceLimits limits,
            Consumer<String> console, ScriptRun run) {
        return ProjectHostBindings.of(args, project, console, limits, run::isCancelled);
    }

    private static void logRun(String logPrefix, String runId, long durationMs, ScriptResult result) {
        final var outcome = result.isError() ? result.getErrorName() + ": " + result.getErrorMessage() : "ok";
        final var metrics = result.getMetrics();
        final var line = logPrefix + " runId=" + runId + " durationMs=" + durationMs + " outcome=" + outcome
                + " instructions=" + metrics.instructions() + " peakMemoryBytes=" + metrics.peakMemoryBytes()
                + " dbOps=" + metrics.dbOperations() + renderStack(result.getErrorStack());
        if (EXHAUSTED_MEMORY_MESSAGE.equals(result.getErrorMessage())) {
            logger.warning(line);
        } else {
            logger.info(line);
        }
    }

    private static OperationResponse toRunScriptResponse(ScriptResult result, String runId) {
        final var response = result.isError()
                ? new RunScriptResponse(result.getErrorName() + ": " + result.getErrorMessage(),
                        errorCodeFor(result.getErrorName()), result.getLogs(), result.isLogsTruncated(), runId,
                        result.getErrorStack())
                : new RunScriptResponse("Script executed successfully", result.getValue(), result.getLogs(),
                        result.isLogsTruncated(), runId);
        response.setMetrics(result.getMetrics().toJson());
        return response;
    }

    static ScriptRunRecord historyOf(String runId, ScriptRunKind kind, String projectName, String name, String username,
            long startedAt, long durationMs, ScriptResult result) {
        return new ScriptRunRecord(runId, kind, projectName, name, name, null, null, username, username, startedAt,
                durationMs, 1, result.isError() ? ScriptRunRecord.OUTCOME_ERROR : ScriptRunRecord.OUTCOME_OK,
                result.getErrorName(), result.getErrorMessage(), result.getErrorStack(), result.getMetrics(),
                result.getLogs(), result.isLogsTruncated());
    }

    public static String renderStack(List<String> stack) {
        return stack == null || stack.isEmpty() ? "" : " stack=[" + String.join(" | ", stack) + "]";
    }

    public static ErrorCode errorCodeFor(String errorName) {
        return switch (errorName) {
            case TIMEOUT -> ErrorCode.SCRIPT_TIMEOUT;
            case CANCELLED -> ErrorCode.SCRIPT_CANCELLED;
            case LIMIT -> ErrorCode.SCRIPT_LIMIT_EXCEEDED;
            case MEMORY -> ErrorCode.SCRIPT_MEMORY_EXCEEDED;
            case ARRAY_TOO_LARGE -> ErrorCode.SCRIPT_ARRAY_TOO_LARGE;
            case RESULT_TOO_LARGE -> ErrorCode.SCRIPT_RESULT_TOO_LARGE;
            case PENDING_RESULT -> ErrorCode.SCRIPT_RESULT_PENDING;
            default -> ErrorCode.SCRIPT_FAILED;
        };
    }
}
