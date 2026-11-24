package org.ccg.mpp.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.ConfigKey;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.EntrypointDefinition;
import org.ccg.mpp.data.ProcedureDefinition;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.http.HttpRequest;
import org.ccg.mpp.http.HttpResponse;
import org.ccg.mpp.http.MalformedEncodingException;
import org.ccg.mpp.http.PercentDecoding;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.log.Logger;
import org.ccg.mpp.ops.entrypoint.EntrypointArgs;
import org.ccg.mpp.ops.entrypoint.EntrypointRouter;
import org.ccg.mpp.ops.entrypoint.MalformedBodyException;
import org.ccg.mpp.ops.entrypoint.ReplyEnvelope;
import org.ccg.mpp.ops.entrypoint.RouteMatch;
import org.ccg.mpp.ops.middleware.MiddlewareChain;
import org.ccg.mpp.ops.middleware.MiddlewareChains;

public final class EntrypointDispatcher {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final EntrypointRouter router = IocContainer.get(EntrypointRouter.class);
    private static final MiddlewareChains chains = IocContainer.get(MiddlewareChains.class);
    private static final ScriptAdmission admission = IocContainer.get(ScriptAdmission.class);
    private static final Configuration configuration = Configuration.getInstance();
    private static final Logger logger = Logger.logFor(EntrypointDispatcher.class);
    private static final String SEPARATOR = "/";
    private static final String NO_ROUTE = "No entrypoint serves this path";
    private static final int ROUTE_ATTEMPTS = 2;

    private record Target(String projectName, String path, List<String> segments) {
    }

    private EntrypointDispatcher() {
    }

    public static HttpResponse handle(HttpRequest request) {
        final Target target;
        try {
            target = targetOf(request.head().path());
        } catch (MalformedEncodingException e) {
            return HttpResponse.error(400, "The path is not valid: " + e.getMessage());
        }
        if (target == null || cache.getAdminProjectEntry(target.projectName()) == null) {
            return HttpResponse.error(404, NO_ROUTE);
        }
        for (var attempt = 0; attempt < ROUTE_ATTEMPTS; attempt++) {
            final var match = router.table(target.projectName()).match(request.head().method(), target.segments());
            switch (match) {
                case RouteMatch.NoRoute ignored -> {
                    return HttpResponse.error(404, NO_ROUTE);
                }
                case RouteMatch.MethodNotAllowed refused -> {
                    return HttpResponse.error(405, "Method not allowed").withHeader("Allow",
                            String.join(", ", refused.allowed()));
                }
                case RouteMatch.Found found -> {
                    final var current = cache.getEntrypoint(target.projectName(), found.definition().getName());
                    if (current != null && current.getVersion() == found.definition().getVersion()) {
                        return serve(request, target, found);
                    }
                    router.reload(target.projectName());
                }
            }
        }
        return HttpResponse.error(404, NO_ROUTE);
    }

    private static Target targetOf(String rawPath) {
        final var parts = new ArrayList<>(List.of(rawPath.substring(1).split(SEPARATOR, -1)));
        if (parts.getFirst().isEmpty()) {
            return null;
        }
        final var projectName = PercentDecoding.decode(parts.removeFirst(), false);
        if (!parts.isEmpty() && parts.getLast().isEmpty()) {
            parts.removeLast();
        }
        final var segments = new ArrayList<String>(parts.size());
        for (final var part : parts) {
            if (part.isEmpty()) {
                return null;
            }
            segments.add(PercentDecoding.decode(part, false));
        }
        final var path = SEPARATOR + String.join(SEPARATOR, parts);
        return new Target(projectName, path, List.copyOf(segments));
    }

    private static HttpResponse serve(HttpRequest request, Target target, RouteMatch.Found found) {
        final var definition = found.definition();
        final var projectName = target.projectName();
        if (!definition.isEnabled()) {
            return HttpResponse.error(404, NO_ROUTE);
        }
        final var procedure = cache.getProcedure(projectName, definition.getProcedureName());
        if (procedure == null || !procedure.isEnabled()) {
            logger.warning("Entrypoint '" + definition.getName() + "' in project '" + projectName
                    + "' was called, but its procedure '" + definition.getProcedureName() + "' is missing or disabled");
            return HttpResponse.error(500, "Internal Server Error");
        }
        final var refusal = DefinerAuthority.refusal(definition.getDefiner(), projectName, null);
        if (refusal != null) {
            DefinerAuthority.disableEntrypoint(projectName, definition, refusal);
            return HttpResponse.error(404, NO_ROUTE);
        }
        if (!configuration.isScriptsEnabled()) {
            return HttpResponse.error(503, "Scripts are disabled on this server");
        }
        final var chain = chains.chainFor(projectName, definition);
        if (chain.isUnreadable()) {
            logger.warning("Entrypoint '" + definition.getName() + "' in project '" + projectName
                    + "' was called, but the project's middlewares are unreadable: " + chain.unreadableReason());
            return ReplyEnvelope.failure(null).toHttpResponse();
        }
        return switch (admission.acquire(definition.getDefiner(), projectName, false)) {
            case ScriptAdmission.Refusal limited ->
                HttpResponse.error(503, "Too many scripts are running (" + limited.scope() + " limit), retry shortly")
                        .withHeader("Retry-After", String.valueOf(Globals.HTTP_RETRY_AFTER_SECONDS));
            case ScriptAdmission.Permit permit -> {
                try (permit) {
                    yield runAdmitted(request, target, found, new Admitted(procedure, chain));
                }
            }
        };
    }

    private record Admitted(ProcedureDefinition procedure, MiddlewareChain chain) {
    }

    private static HttpResponse runAdmitted(HttpRequest request, Target target, RouteMatch.Found found,
            Admitted admitted) {
        final var definition = found.definition();
        final byte[] body;
        try {
            body = request.body();
        } catch (IOException e) {
            return HttpResponse.error(408, "The request body did not arrive in time").closing();
        }
        final JsonObject args;
        try {
            args = EntrypointArgs.of(request, definition.getName(), target.path(), found.pathParams(), body);
        } catch (MalformedBodyException e) {
            return HttpResponse.error(400, e.getMessage());
        }
        final var chainRequest = new MiddlewareChainRunner.ChainRequest(target.projectName(), definition,
                request.head().method(), target.path(), found.pathParams().keySet(),
                System.currentTimeMillis() + timeoutMillis(definition));
        return MiddlewareChainRunner.run(chainRequest, args, admitted.chain(), admitted.procedure()).toHttpResponse();
    }

    static long timeoutMillis(EntrypointDefinition definition) {
        final var requested = definition.getTimeoutMs() > 0
                ? definition.getTimeoutMs()
                : configuration.longValue(ConfigKey.ENTRYPOINT_TIMEOUT_MS);
        return Math.min(requested, configuration.longValue(ConfigKey.ENTRYPOINT_MAX_TIMEOUT_MS));
    }
}
