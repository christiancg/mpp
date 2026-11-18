package io.github.christiancg.mpp.client.internal;

import io.github.christiancg.mpp.client.Document;
import io.github.christiancg.mpp.client.admin.Permissions;
import io.github.christiancg.mpp.client.admin.TransactionDecision;
import io.github.christiancg.mpp.client.admin.TriggerRunDecision;
import io.github.christiancg.mpp.client.admin.TriggerRunStatus;
import io.github.christiancg.mpp.client.protocol.Operation;
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

    private final PipelineEncoder pipeline;

    public ScriptRequestEncoder(PipelineEncoder pipeline) {
        this.pipeline = pipeline;
    }

    public ObjectNode runScript(String database, String script) {
        return RequestEncoder.of(Operation.RUN_SCRIPT, database).put("script", script);
    }

    public ObjectNode runScript(String database, String script, Document args) {
        return withArgs(runScript(database, script), args);
    }

    public ObjectNode saveProcedure(String database, ProcedureDefinition procedure) {
        final var request = RequestEncoder.of(Operation.SAVE_PROCEDURE, database).put(NAME, procedure.name())
                .put("script", procedure.script());
        procedure.description().ifPresent(description -> request.put(DESCRIPTION, description));
        procedure.enabled().ifPresent(enabled -> request.put(ENABLED, enabled));
        procedure.ifVersion().ifPresent(version -> request.put(IF_VERSION, version));
        return request;
    }

    public ObjectNode deleteProcedure(String database, String name) {
        return RequestEncoder.of(Operation.DELETE_PROCEDURE, database).put(NAME, name);
    }

    public ObjectNode listProcedures(String database) {
        return RequestEncoder.of(Operation.LIST_PROCEDURES, database);
    }

    public ObjectNode listProcedures(String database, boolean includeSource) {
        return listProcedures(database).put("includeSource", includeSource);
    }

    public ObjectNode callProcedure(String database, String name) {
        return RequestEncoder.of(Operation.CALL_PROCEDURE, database).put(PROCEDURE, name);
    }

    public ObjectNode callProcedure(String database, String name, Document args) {
        return withArgs(callProcedure(database, name), args);
    }

    public ObjectNode saveTrigger(String database, String collection, TriggerDefinition trigger) {
        final var request = RequestEncoder.of(Operation.SAVE_TRIGGER, database, collection).put(NAME, trigger.name());
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

    public ObjectNode deleteTrigger(String database, String collection, String name) {
        return RequestEncoder.of(Operation.DELETE_TRIGGER, database, collection).put(NAME, name);
    }

    public ObjectNode listTriggers(String database) {
        return RequestEncoder.of(Operation.LIST_TRIGGERS, database);
    }

    public ObjectNode listTriggers(String database, String collection) {
        return RequestEncoder.of(Operation.LIST_TRIGGERS, database, collection);
    }

    public ObjectNode testTrigger(String database, String collection, String name, TriggerEvent event,
            Document document) {
        return RequestEncoder.of(Operation.TEST_TRIGGER, database, collection).put(NAME, name)
                .put("event", event.name()).set("document", Documents.node(document));
    }

    public ObjectNode saveSchedule(String database, ScheduleDefinition schedule) {
        final var request = RequestEncoder.of(Operation.SAVE_SCHEDULE, database).put(NAME, schedule.name())
                .put(PROCEDURE, schedule.procedureName());
        schedule.cron().ifPresent(cron -> request.put("cron", cron));
        schedule.interval().ifPresent(interval -> request.put("intervalMs", interval.toMillis()));
        schedule.args().ifPresent(args -> request.set(ARGS, Documents.node(args)));
        schedule.timeout().ifPresent(timeout -> request.put("timeoutMs", timeout.toMillis()));
        schedule.description().ifPresent(description -> request.put(DESCRIPTION, description));
        schedule.enabled().ifPresent(enabled -> request.put(ENABLED, enabled));
        schedule.ifVersion().ifPresent(version -> request.put(IF_VERSION, version));
        return request;
    }

    public ObjectNode deleteSchedule(String database, String name) {
        return RequestEncoder.of(Operation.DELETE_SCHEDULE, database).put(NAME, name);
    }

    public ObjectNode listSchedules(String database) {
        return RequestEncoder.of(Operation.LIST_SCHEDULES, database);
    }

    public ObjectNode listSchedules(String database, boolean includeArgs) {
        return listSchedules(database).put("includeArgs", includeArgs);
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
        names(request.putObject("databasePermissions"), permissions.databases());
        names(request.putObject("collectionPermissions"), permissions.collections());
        names(request.putObject("scriptPermissions"), permissions.scripts());
        return request;
    }

    private static void names(ObjectNode target, Map<String, ? extends Enum<?>> grants) {
        grants.forEach((key, level) -> target.put(key, level.name()));
    }

    private static ObjectNode withArgs(ObjectNode request, Document args) {
        return request.set(ARGS, Documents.node(args));
    }
}
