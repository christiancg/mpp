package io.github.christiancg.mpp.client.internal;

import io.github.christiancg.mpp.client.Document;
import io.github.christiancg.mpp.client.admin.Permissions;
import io.github.christiancg.mpp.client.admin.TransactionDecision;
import io.github.christiancg.mpp.client.admin.TriggerRunDecision;
import io.github.christiancg.mpp.client.admin.TriggerRunStatus;
import io.github.christiancg.mpp.client.protocol.Operation;
import io.github.christiancg.mpp.client.scripting.EntrypointDefinition;
import io.github.christiancg.mpp.client.scripting.MiddlewareCriteria;
import io.github.christiancg.mpp.client.scripting.MiddlewareDefinition;
import io.github.christiancg.mpp.client.scripting.MiddlewareMatch;
import io.github.christiancg.mpp.client.scripting.ProcedureDefinition;
import io.github.christiancg.mpp.client.scripting.ScheduleDefinition;
import io.github.christiancg.mpp.client.scripting.TriggerDefinition;
import io.github.christiancg.mpp.client.scripting.TriggerEvent;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.node.ObjectNode;

public final class ScriptRequestEncoder {
    private static final String NAME = "name";
    private static final String ARGS = "args";
    private static final String USERNAME = "username";
    private static final String RUN_ID = "runId";
    private static final String DECISION = "decision";
    private static final String ENABLED = "enabled";
    private static final String IF_VERSION = "ifVersion";
    private static final String DESCRIPTION = "description";
    private static final String PROCEDURE = "procedureName";
    private static final String SCRIPT = "script";
    private static final String TIMEOUT = "timeoutMs";

    private final PipelineEncoder pipeline;

    public ScriptRequestEncoder(PipelineEncoder pipeline) {
        this.pipeline = pipeline;
    }

    public ObjectNode runScript(String project, String script) {
        return RequestEncoder.of(Operation.RUN_SCRIPT, project).put(SCRIPT, script);
    }

    public ObjectNode runScript(String project, String script, Document args) {
        return withArgs(runScript(project, script), args);
    }

    public ObjectNode saveProcedure(String project, ProcedureDefinition procedure) {
        final var request = RequestEncoder.of(Operation.SAVE_PROCEDURE, project).put(NAME, procedure.name()).put(SCRIPT,
                procedure.script());
        procedure.description().ifPresent(description -> request.put(DESCRIPTION, description));
        procedure.enabled().ifPresent(enabled -> request.put(ENABLED, enabled));
        procedure.ifVersion().ifPresent(version -> request.put(IF_VERSION, version));
        return request;
    }

    public ObjectNode deleteProcedure(String project, String name) {
        return RequestEncoder.of(Operation.DELETE_PROCEDURE, project).put(NAME, name);
    }

    public ObjectNode listProcedures(String project) {
        return RequestEncoder.of(Operation.LIST_PROCEDURES, project);
    }

    public ObjectNode listProcedures(String project, boolean includeSource) {
        return listProcedures(project).put("includeSource", includeSource);
    }

    public ObjectNode callProcedure(String project, String name) {
        return RequestEncoder.of(Operation.CALL_PROCEDURE, project).put(PROCEDURE, name);
    }

    public ObjectNode callProcedure(String project, String name, Document args) {
        return withArgs(callProcedure(project, name), args);
    }

    public ObjectNode saveTrigger(String project, String collection, TriggerDefinition trigger) {
        final var request = RequestEncoder.of(Operation.SAVE_TRIGGER, project, collection).put(NAME, trigger.name());
        final var events = request.putArray("events");
        trigger.events().forEach(event -> events.add(event.name()));
        request.put(PROCEDURE, trigger.procedureName());
        trigger.timing().ifPresent(timing -> request.put("timing", timing.wireName()));
        trigger.mode().ifPresent(mode -> request.put("mode", mode.wireName()));
        trigger.allowCascade().ifPresent(cascade -> request.put("allowCascade", cascade));
        trigger.enabled().ifPresent(enabled -> request.put(ENABLED, enabled));
        trigger.ifVersion().ifPresent(version -> request.put(IF_VERSION, version));
        return request;
    }

    public ObjectNode deleteTrigger(String project, String collection, String name) {
        return RequestEncoder.of(Operation.DELETE_TRIGGER, project, collection).put(NAME, name);
    }

    public ObjectNode listTriggers(String project) {
        return RequestEncoder.of(Operation.LIST_TRIGGERS, project);
    }

    public ObjectNode listTriggers(String project, String collection) {
        return RequestEncoder.of(Operation.LIST_TRIGGERS, project, collection);
    }

    public ObjectNode testTrigger(String project, String collection, String name, TriggerEvent event,
            Document document) {
        return RequestEncoder.of(Operation.TEST_TRIGGER, project, collection).put(NAME, name).put("event", event.name())
                .set("document", Documents.node(document));
    }

    public ObjectNode saveSchedule(String project, ScheduleDefinition schedule) {
        final var request = RequestEncoder.of(Operation.SAVE_SCHEDULE, project).put(NAME, schedule.name())
                .put(PROCEDURE, schedule.procedureName());
        schedule.cron().ifPresent(cron -> request.put("cron", cron));
        schedule.interval().ifPresent(interval -> request.put("intervalMs", interval.toMillis()));
        schedule.args().ifPresent(args -> request.set(ARGS, Documents.node(args)));
        schedule.timeout().ifPresent(timeout -> request.put(TIMEOUT, timeout.toMillis()));
        schedule.description().ifPresent(description -> request.put(DESCRIPTION, description));
        schedule.enabled().ifPresent(enabled -> request.put(ENABLED, enabled));
        schedule.ifVersion().ifPresent(version -> request.put(IF_VERSION, version));
        return request;
    }

    public ObjectNode deleteSchedule(String project, String name) {
        return RequestEncoder.of(Operation.DELETE_SCHEDULE, project).put(NAME, name);
    }

    public ObjectNode listSchedules(String project) {
        return RequestEncoder.of(Operation.LIST_SCHEDULES, project);
    }

    public ObjectNode listSchedules(String project, boolean includeArgs) {
        return listSchedules(project).put("includeArgs", includeArgs);
    }

    public ObjectNode saveEntrypoint(String project, EntrypointDefinition entrypoint) {
        final var request = RequestEncoder.of(Operation.SAVE_ENTRYPOINT, project).put(NAME, entrypoint.name())
                .put("method", entrypoint.method().name()).put("path", entrypoint.path());
        entrypoint.procedureName().ifPresent(procedureName -> request.put(PROCEDURE, procedureName));
        entrypoint.procedure().ifPresent(procedure -> request.set("procedure", inlineProcedure(procedure)));
        entrypoint.timeout().ifPresent(timeout -> request.put(TIMEOUT, timeout.toMillis()));
        entrypoint.description().ifPresent(description -> request.put(DESCRIPTION, description));
        entrypoint.enabled().ifPresent(enabled -> request.put(ENABLED, enabled));
        entrypoint.ifVersion().ifPresent(version -> request.put(IF_VERSION, version));
        return request;
    }

    public ObjectNode deleteEntrypoint(String project, String name) {
        return RequestEncoder.of(Operation.DELETE_ENTRYPOINT, project).put(NAME, name);
    }

    public ObjectNode listEntrypoints(String project) {
        return RequestEncoder.of(Operation.LIST_ENTRYPOINTS, project);
    }

    public ObjectNode saveMiddleware(String project, MiddlewareDefinition middleware) {
        final var request = RequestEncoder.of(Operation.SAVE_MIDDLEWARE, project).put(NAME, middleware.name())
                .put("phase", middleware.phase().name());
        middleware.order().ifPresent(order -> request.put("order", order));
        middleware.match().ifPresent(match -> request.set("match", match(match)));
        middleware.procedureName().ifPresent(procedureName -> request.put(PROCEDURE, procedureName));
        middleware.procedure().ifPresent(procedure -> request.set("procedure", inlineProcedure(procedure)));
        middleware.timeout().ifPresent(timeout -> request.put(TIMEOUT, timeout.toMillis()));
        middleware.description().ifPresent(description -> request.put(DESCRIPTION, description));
        middleware.enabled().ifPresent(enabled -> request.put(ENABLED, enabled));
        middleware.ifVersion().ifPresent(version -> request.put(IF_VERSION, version));
        return request;
    }

    public ObjectNode deleteMiddleware(String project, String name) {
        return RequestEncoder.of(Operation.DELETE_MIDDLEWARE, project).put(NAME, name);
    }

    public ObjectNode listMiddlewares(String project) {
        return RequestEncoder.of(Operation.LIST_MIDDLEWARES, project);
    }

    public ObjectNode listScripts() {
        return RequestEncoder.of(Operation.LIST_SCRIPTS);
    }

    public ObjectNode cancelScript(String runId) {
        return RequestEncoder.of(Operation.CANCEL_SCRIPT).put(RUN_ID, runId);
    }

    public ObjectNode listTriggerRuns() {
        return RequestEncoder.of(Operation.LIST_TRIGGER_RUNS);
    }

    public ObjectNode listTriggerRuns(TriggerRunStatus status) {
        return listTriggerRuns().put("status", status.name());
    }

    public ObjectNode resolveTriggerRun(String runId, TriggerRunDecision decision) {
        return RequestEncoder.of(Operation.RESOLVE_TRIGGER_RUN).put(RUN_ID, runId).put(DECISION, decision.wireName());
    }

    public ObjectNode listTransactions() {
        return RequestEncoder.of(Operation.LIST_TRANSACTIONS);
    }

    public ObjectNode resolveTransaction(String dtxId, TransactionDecision decision) {
        return RequestEncoder.of(Operation.RESOLVE_TRANSACTION).put("dtxId", dtxId).put(DECISION, decision.wireName());
    }

    public ObjectNode createUser(String username, String password) {
        return RequestEncoder.of(Operation.CREATE_USER).put(USERNAME, username).put("password", password);
    }

    public ObjectNode createUser(String username, String password, Permissions permissions) {
        return permissions(createUser(username, password), permissions);
    }

    public ObjectNode deleteUser(String username) {
        return RequestEncoder.of(Operation.DELETE_USER).put(USERNAME, username);
    }

    public ObjectNode changePermissions(String username, Permissions permissions) {
        return permissions(RequestEncoder.of(Operation.CHANGE_PERMISSIONS).put(USERNAME, username), permissions);
    }

    public ObjectNode setPassword(String username, String newPassword) {
        return RequestEncoder.of(Operation.SET_PASSWORD).put(USERNAME, username).put("newPassword", newPassword);
    }

    public ObjectNode setPassword(String username, String currentPassword, String newPassword) {
        return RequestEncoder.of(Operation.SET_PASSWORD).put(USERNAME, username).put("currentPassword", currentPassword)
                .put("newPassword", newPassword);
    }

    public ObjectNode listUsers(List<PipelineStep> steps) {
        final var request = RequestEncoder.of(Operation.LIST_USERS);
        if (!steps.isEmpty()) {
            request.set(RequestEncoder.STEPS, pipeline.steps(steps));
        }
        return request;
    }

    private static ObjectNode permissions(ObjectNode request, Permissions permissions) {
        request.put("admin", permissions.admin());
        final var global = request.putArray("globalPermissions");
        permissions.global().forEach(permission -> global.add(permission.name()));
        names(request.putObject("projectPermissions"), permissions.projects());
        names(request.putObject("collectionPermissions"), permissions.collections());
        names(request.putObject("scriptPermissions"), permissions.scripts());
        return request;
    }

    private static void names(ObjectNode target, Map<String, ? extends Enum<?>> grants) {
        grants.forEach((key, level) -> target.put(key, level.name()));
    }

    private static ObjectNode inlineProcedure(ProcedureDefinition procedure) {
        final var inline = RequestEncoder.NODES.objectNode().put(NAME, procedure.name()).put(SCRIPT,
                procedure.script());
        procedure.description().ifPresent(description -> inline.put(DESCRIPTION, description));
        procedure.enabled().ifPresent(enabled -> inline.put(ENABLED, enabled));
        return inline;
    }

    private static ObjectNode match(MiddlewareMatch match) {
        final var node = criteria(match.include());
        if (!match.exclude().isEmpty()) {
            final var exclude = node.putArray("exclude");
            match.exclude().forEach(rule -> exclude.add(criteria(rule)));
        }
        return node;
    }

    private static ObjectNode criteria(MiddlewareCriteria criteria) {
        final var node = RequestEncoder.NODES.objectNode();
        if (!criteria.methods().isEmpty()) {
            final var methods = node.putArray("methods");
            criteria.methods().forEach(method -> methods.add(method.name()));
        }
        criteria.pathPrefix().ifPresent(pathPrefix -> node.put("pathPrefix", pathPrefix));
        if (!criteria.entrypoints().isEmpty()) {
            final var entrypoints = node.putArray("entrypoints");
            criteria.entrypoints().forEach(entrypoints::add);
        }
        return node;
    }

    private static ObjectNode withArgs(ObjectNode request, Document args) {
        return request.set(ARGS, Documents.node(args));
    }
}
