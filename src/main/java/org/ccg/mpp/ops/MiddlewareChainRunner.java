package org.ccg.mpp.ops;

import java.util.Locale;
import java.util.Set;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.data.EntrypointDefinition;
import org.ccg.mpp.data.MiddlewareDefinition;
import org.ccg.mpp.data.ProcedureDefinition;
import org.ccg.mpp.ejson.elements.JsonBaseElement;
import org.ccg.mpp.ejson.elements.JsonNumber;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.log.Logger;
import org.ccg.mpp.ops.entrypoint.EntrypointReply;
import org.ccg.mpp.ops.entrypoint.InvalidEnvelopeException;
import org.ccg.mpp.ops.entrypoint.ReplyEnvelope;
import org.ccg.mpp.ops.middleware.MiddlewareChain;
import org.ccg.mpp.simplejs.builtins.MiddlewareModule;
import org.ccg.mpp.simplejs.host.MiddlewareDecision;
import org.ccg.mpp.simplejs.host.MiddlewareHostBindings;
import org.ccg.mpp.simplejs.host.ProjectHostBindings;

final class MiddlewareChainRunner {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final CompiledProcedureCache compiledProcedures = IocContainer.get(CompiledProcedureCache.class);
    private static final Logger logger = Logger.logFor(MiddlewareChainRunner.class);
    private static final String MIDDLEWARE_ARG = "middleware";
    private static final String RESPONSE_ARG = "response";

    record ChainRequest(String projectName, EntrypointDefinition entrypoint, String method, String path,
            Set<String> declaredPathParams, long deadlineMillis) {
        String describe() {
            return "entrypoint '" + entrypoint.getName() + "' in project '" + projectName + "' (" + method + " " + path
                    + ")";
        }
    }

    private sealed interface Step {
        record Continue(JsonObject changes, ReplyEnvelope response) implements Step {
        }

        record Respond(ReplyEnvelope envelope) implements Step {
        }

        record Failed(ReplyEnvelope failure) implements Step {
        }

        record Abort(ReplyEnvelope failure) implements Step {
        }
    }

    private record Preflight(ProcedureDefinition procedure, String refusal) {
    }

    private MiddlewareChainRunner() {
    }

    static ReplyEnvelope run(ChainRequest request, JsonObject args, MiddlewareChain chain, ProcedureDefinition target) {
        final var locals = new JsonObject();
        ReplyEnvelope response = null;
        for (final var middleware : chain.ingress()) {
            switch (runMiddleware(request, middleware, argsFor(args, locals, middleware, null), null)) {
                case Step.Abort abort -> {
                    return abort.failure();
                }
                case Step.Failed failed -> response = failed.failure();
                case Step.Respond respond -> response = respond.envelope();
                case Step.Continue next -> applyToRequest(args, locals, next.changes());
            }
            if (response != null) {
                break;
            }
        }
        if (response == null) {
            final var targetArgs = copyOf(args);
            targetArgs.add(MiddlewareModule.LOCALS, copyOf(locals));
            response = runTarget(request, targetArgs, target);
            if (response == null) {
                return ReplyEnvelope.failure(null);
            }
        }
        return runEgress(request, args, locals, chain, response);
    }

    private static ReplyEnvelope runEgress(ChainRequest request, JsonObject args, JsonObject locals,
            MiddlewareChain chain, ReplyEnvelope produced) {
        var response = produced;
        for (final var middleware : chain.egress()) {
            switch (runMiddleware(request, middleware, argsFor(args, locals, middleware, response), response)) {
                case Step.Abort abort -> {
                    return abort.failure();
                }
                case Step.Failed failed -> {
                    return failed.failure();
                }
                case Step.Respond respond -> {
                    return respond.envelope();
                }
                case Step.Continue next -> {
                    mergeInto(locals, next.changes().get(MiddlewareModule.LOCALS), false);
                    response = next.response();
                }
            }
        }
        return response;
    }

    private static ReplyEnvelope runTarget(ChainRequest request, JsonObject targetArgs, ProcedureDefinition target) {
        final var timeout = request.deadlineMillis() - System.currentTimeMillis();
        if (timeout <= 0) {
            logger.warning("The " + request.describe() + " ran out of its timeout before its procedure started");
            return null;
        }
        final var entrypoint = request.entrypoint();
        final var definer = entrypoint.getDefiner();
        final var compiled = compiledProcedures.get(request.projectName(), entrypoint.getProcedureName(),
                target.getVersion(), target.getSource());
        final var logPrefix = "ENTRYPOINT name=" + entrypoint.getName() + " project=" + request.projectName()
                + " procedure=" + entrypoint.getProcedureName() + " definer=" + definer + " method=" + request.method()
                + " path=" + request.path();
        final var start = System.currentTimeMillis();
        final var outcome = ScriptOperationHelper.runCompiled(compiled, targetArgs, request.projectName(), definer,
                null, logPrefix, ScriptRunKind.ENTRYPOINT, entrypoint.getName(),
                ProjectHostBindings.limitsFromConfiguration().withWallClockMillis(timeout), null);
        ChainRunHistory.record(ScriptRunKind.ENTRYPOINT, request.projectName(), entrypoint.getName(),
                entrypoint.getProcedureName(), definer, start, outcome);
        return EntrypointReply.envelopeOf(outcome.result(), outcome.runId());
    }

    private static Step runMiddleware(ChainRequest request, MiddlewareDefinition middleware, JsonObject runArgs,
            ReplyEnvelope response) {
        final var preflight = preflight(request, middleware);
        if (preflight.refusal() != null) {
            logger.warning("Middleware '" + middleware.getName() + "' blocks the " + request.describe() + ": "
                    + preflight.refusal() + "; answering 500");
            return new Step.Abort(ReplyEnvelope.failure(null));
        }
        final var remaining = request.deadlineMillis() - System.currentTimeMillis();
        if (remaining <= 0) {
            logger.warning("The " + request.describe() + " ran out of its timeout before middleware '"
                    + middleware.getName() + "' started");
            return new Step.Abort(ReplyEnvelope.failure(null));
        }
        final var timeout = middleware.getTimeoutMs() > 0 ? Math.min(middleware.getTimeoutMs(), remaining) : remaining;
        final var decision = new MiddlewareDecision(middleware.getPhase(), request.declaredPathParams());
        final var procedure = preflight.procedure();
        final var compiled = compiledProcedures.get(request.projectName(), middleware.getProcedureName(),
                procedure.getVersion(), procedure.getSource());
        final var logPrefix = "MIDDLEWARE name=" + middleware.getName() + " phase=" + middleware.getPhase()
                + " project=" + request.projectName() + " entrypoint=" + request.entrypoint().getName() + " procedure="
                + middleware.getProcedureName() + " definer=" + middleware.getDefiner() + " method=" + request.method()
                + " path=" + request.path();
        final var start = System.currentTimeMillis();
        final var outcome = ScriptOperationHelper.runCompiled(compiled, runArgs, request.projectName(),
                middleware.getDefiner(), null, logPrefix, ScriptRunKind.MIDDLEWARE, middleware.getName(),
                ProjectHostBindings.limitsFromConfiguration().withWallClockMillis(timeout), null,
                host -> new MiddlewareHostBindings(host, decision));
        ChainRunHistory.record(ScriptRunKind.MIDDLEWARE, request.projectName(), middleware.getName(),
                middleware.getProcedureName(), middleware.getDefiner(), start, outcome);
        if (outcome.result().isError()) {
            return new Step.Failed(ReplyEnvelope.failure(outcome.runId()));
        }
        return stepOf(request, middleware, outcome.runId(), decision.outcome(), response);
    }

    private static Step stepOf(ChainRequest request, MiddlewareDefinition middleware, String runId,
            MiddlewareDecision.Outcome decided, ReplyEnvelope response) {
        try {
            return switch (decided) {
                case null -> {
                    logger.warning("Middleware '" + middleware.getName() + "' run " + runId + " on the "
                            + request.describe() + " called neither next() nor reject(); answering 500");
                    yield new Step.Abort(ReplyEnvelope.failure(runId));
                }
                case MiddlewareDecision.Outcome.Reject reject ->
                    new Step.Respond(ReplyEnvelope.of(reject.status(), reject.headers(), reject.body()));
                case MiddlewareDecision.Outcome.Next next ->
                    new Step.Continue(next.changes(), response == null ? null : response.withChanges(next.changes()));
            };
        } catch (InvalidEnvelopeException e) {
            logger.warning("Middleware '" + middleware.getName() + "' run " + runId + " on the " + request.describe()
                    + " decided a response the server cannot send: " + e.getMessage());
            return new Step.Failed(ReplyEnvelope.failure(runId));
        }
    }

    private static Preflight preflight(ChainRequest request, MiddlewareDefinition middleware) {
        if (middleware.isRevoked()) {
            return new Preflight(null, "it was disabled: " + middleware.getDisabledReason());
        }
        final var procedure = cache.getProcedure(request.projectName(), middleware.getProcedureName());
        if (procedure == null || !procedure.isEnabled()) {
            return new Preflight(null, "its procedure '" + middleware.getProcedureName() + "' is missing or disabled");
        }
        final var refusal = DefinerAuthority.refusal(middleware.getDefiner(), request.projectName(), null);
        if (refusal != null) {
            DefinerAuthority.disableMiddleware(request.projectName(), middleware, refusal);
            return new Preflight(null, refusal);
        }
        return new Preflight(procedure, null);
    }

    private static JsonObject argsFor(JsonObject args, JsonObject locals, MiddlewareDefinition middleware,
            ReplyEnvelope response) {
        final var runArgs = copyOf(args);
        final var about = new JsonObject();
        about.add("name", new JsonString(middleware.getName()));
        about.add("phase", new JsonString(middleware.getPhase().name()));
        about.add("order", new JsonNumber(middleware.getOrder()));
        runArgs.add(MIDDLEWARE_ARG, about);
        runArgs.add(MiddlewareModule.LOCALS, copyOf(locals));
        if (response != null) {
            runArgs.add(RESPONSE_ARG, response.toArgs());
        }
        return runArgs;
    }

    private static void applyToRequest(JsonObject args, JsonObject locals, JsonObject changes) {
        mergeInto(objectAt(args, MiddlewareModule.HEADERS), changes.get(MiddlewareModule.HEADERS), true);
        mergeInto(objectAt(args, MiddlewareModule.QUERY), changes.get(MiddlewareModule.QUERY), false);
        mergeInto(objectAt(args, MiddlewareModule.PATH_PARAMS), changes.get(MiddlewareModule.PATH_PARAMS), false);
        if (changes.has(MiddlewareModule.BODY)) {
            args.add(MiddlewareModule.BODY, changes.get(MiddlewareModule.BODY));
        }
        mergeInto(locals, changes.get(MiddlewareModule.LOCALS), false);
    }

    private static JsonObject objectAt(JsonObject args, String key) {
        final var current = args.get(key);
        final var copy = current != null && current.isJsonObject() ? copyOf(current.asJsonObject()) : new JsonObject();
        args.add(key, copy);
        return copy;
    }

    private static void mergeInto(JsonObject target, JsonBaseElement changes, boolean lowerCaseKeys) {
        if (changes == null || !changes.isJsonObject()) {
            return;
        }
        for (final var entry : changes.asJsonObject().entrySet()) {
            final var key = lowerCaseKeys ? entry.getKey().toLowerCase(Locale.ROOT) : entry.getKey();
            if (entry.getValue().isJsonNull()) {
                target.remove(key);
            } else {
                target.add(key, entry.getValue());
            }
        }
    }

    private static JsonObject copyOf(JsonObject source) {
        final var copy = new JsonObject();
        for (final var entry : source.entrySet()) {
            copy.add(entry.getKey(), entry.getValue());
        }
        return copy;
    }
}
