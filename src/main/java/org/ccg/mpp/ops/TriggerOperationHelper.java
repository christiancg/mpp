package org.ccg.mpp.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.ccg.mpp.bckg_ops.events.EventType;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.ConfigKey;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.TriggerDefinition;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.admin.AdminRecordKey;
import org.ccg.mpp.ops.admin.AdminStamp;
import org.ccg.mpp.ops.admin.StoredDefinitions;
import org.ccg.mpp.ops.req.DeleteTriggerRequest;
import org.ccg.mpp.ops.req.ListTriggersRequest;
import org.ccg.mpp.ops.req.SaveTriggerRequest;
import org.ccg.mpp.ops.req.TestTriggerRequest;
import org.ccg.mpp.ops.resp.ListTriggersResponse;
import org.ccg.mpp.ops.resp.OperationResponse;
import org.ccg.mpp.ops.resp.SaveTriggerResponse;
import org.ccg.mpp.ops.resp.TestTriggerResponse;

public final class TriggerOperationHelper {
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final Configuration configuration = Configuration.getInstance();

    private TriggerOperationHelper() {
    }

    public static OperationResponse executeSave(SaveTriggerRequest request, String actingUser)
            throws IOException, InterruptedException {
        final var projectName = request.getProjectName();
        final var collName = request.getCollectionName();
        if (cache.getAdminCollectionEntry(projectName, collName) == null) {
            return new OperationResponse(OperationType.SAVE_TRIGGER, "Collection '" + collName + "' not found",
                    ErrorCode.PROJECT_NOT_FOUND);
        }
        if (Globals.SCRIPT_RUNS_COLLECTION_NAME.equals(collName)) {
            return new OperationResponse(
                    OperationType.SAVE_TRIGGER, ErrorCode.INVALID_TRIGGER.getDefaultMessage()
                            + ": the reserved collection '" + collName + "' never fires triggers",
                    ErrorCode.INVALID_TRIGGER);
        }
        final var events = new LinkedHashSet<EventType>();
        if (request.getEvents().isEmpty()) {
            return new OperationResponse(OperationType.SAVE_TRIGGER,
                    ErrorCode.INVALID_TRIGGER.getDefaultMessage() + ": at least one event is required",
                    ErrorCode.INVALID_TRIGGER);
        }
        for (final var event : request.getEvents()) {
            final var parsed = parseEvent(event);
            if (parsed == null) {
                return new OperationResponse(OperationType.SAVE_TRIGGER, ErrorCode.INVALID_TRIGGER.getDefaultMessage()
                        + ": unknown event '" + event + "' (expected CREATED, UPDATED or DELETED)",
                        ErrorCode.INVALID_TRIGGER);
            }
            events.add(parsed);
        }
        final var mode = request.getMode() == null ? TriggerDefinition.MODE_DOCUMENT : request.getMode();
        if (!TriggerDefinition.MODE_DOCUMENT.equals(mode) && !TriggerDefinition.MODE_BATCH.equals(mode)) {
            return new OperationResponse(OperationType.SAVE_TRIGGER,
                    ErrorCode.INVALID_TRIGGER.getDefaultMessage() + ": mode must be 'document' or 'batch'",
                    ErrorCode.INVALID_TRIGGER);
        }
        final var timing = request.getTiming() == null ? TriggerDefinition.TIMING_AFTER : request.getTiming();
        final var timingError = validateTiming(timing, mode, request.isAllowCascade());
        if (timingError != null) {
            return timingError;
        }
        if (lacksEnabledProcedure(projectName, request.getProcedureName())) {
            return procedureNotFound(projectName, request.getProcedureName());
        }
        locks.lock(projectName, Globals.PROCEDURES_FOLDER);
        try {
            if (lacksEnabledProcedure(projectName, request.getProcedureName())) {
                return procedureNotFound(projectName, request.getProcedureName());
            }
            return persistDefinition(request, actingUser, mode, timing, events);
        } finally {
            locks.release(projectName, Globals.PROCEDURES_FOLDER);
        }
    }

    private static boolean lacksEnabledProcedure(String projectName, String procedureName) {
        final var procedure = cache.getProcedure(projectName, procedureName);
        return procedure == null || !procedure.isEnabled();
    }

    private static OperationResponse procedureNotFound(String projectName, String procedureName) {
        return new OperationResponse(OperationType.SAVE_TRIGGER,
                "Procedure '" + procedureName + "' not found in project '" + projectName + "'",
                ErrorCode.PROCEDURE_NOT_FOUND);
    }

    private static OperationResponse persistDefinition(SaveTriggerRequest request, String actingUser, String mode,
            String timing, LinkedHashSet<EventType> events) throws IOException {
        final var projectName = request.getProjectName();
        final var collName = request.getCollectionName();
        final var existingList = new ArrayList<>(cache.getTriggersFor(projectName, collName));
        final var existing = findByName(existingList, request.getName());
        if (request.conflictsWith(existing == null ? 0L : existing.getVersion())) {
            return new OperationResponse(OperationType.SAVE_TRIGGER, ErrorCode.PROCEDURE_VERSION_CONFLICT);
        }
        final var foreign = existing == null
                ? null
                : ProcedureBindingGuard.foreignDefinitionRefusal(OperationType.SAVE_TRIGGER, projectName,
                        existing.getDefiner(), actingUser);
        if (foreign != null) {
            return foreign;
        }
        final var maxTriggers = configuration.intValue(ConfigKey.TRIGGER_MAX_PER_COLLECTION);
        if (existing == null && existingList.size() >= maxTriggers) {
            return new OperationResponse(OperationType.SAVE_TRIGGER,
                    ErrorCode.TOO_MANY_TRIGGERS.getDefaultMessage() + " (" + maxTriggers + ")",
                    ErrorCode.TOO_MANY_TRIGGERS);
        }
        final var definition = newDefinition(request, existing, actingUser, mode, timing, events);
        existingList.removeIf(trigger -> trigger.getName().equals(definition.getName()));
        existingList.add(definition);
        persist(projectName, collName, existingList);
        return new SaveTriggerResponse("Trigger saved successfully", definition.getVersion(), definition.getDefiner());
    }

    private static OperationResponse validateTiming(String timing, String mode, boolean allowCascade) {
        if (!TriggerDefinition.TIMING_AFTER.equals(timing) && !TriggerDefinition.TIMING_BEFORE.equals(timing)) {
            return new OperationResponse(OperationType.SAVE_TRIGGER,
                    ErrorCode.INVALID_TRIGGER.getDefaultMessage() + ": timing must be 'before' or 'after'",
                    ErrorCode.INVALID_TRIGGER);
        }
        if (!TriggerDefinition.TIMING_BEFORE.equals(timing)) {
            return null;
        }
        if (TriggerDefinition.MODE_BATCH.equals(mode)) {
            return new OperationResponse(OperationType.SAVE_TRIGGER,
                    ErrorCode.INVALID_TRIGGER.getDefaultMessage() + ": a before trigger does not support batch mode",
                    ErrorCode.INVALID_TRIGGER);
        }
        if (allowCascade) {
            return new OperationResponse(OperationType.SAVE_TRIGGER,
                    ErrorCode.INVALID_TRIGGER.getDefaultMessage()
                            + ": a before trigger cannot allow cascade, because it performs no writes",
                    ErrorCode.INVALID_TRIGGER);
        }
        return null;
    }

    private static TriggerDefinition newDefinition(SaveTriggerRequest request, TriggerDefinition existing,
            String actingUser, String mode, String timing, LinkedHashSet<EventType> events) {
        final var version = existing == null ? 1L : existing.getVersion() + 1;
        final var updatedAt = System.currentTimeMillis();
        final var createdAt = existing == null ? updatedAt : existing.getCreatedAt();
        return new TriggerDefinition(request.getName(), events, request.getProcedureName(), mode, timing,
                request.isAllowCascade(), request.isEnabled(), actingUser, version, createdAt, updatedAt, actingUser);
    }

    public static OperationResponse executeTest(TestTriggerRequest request, String actingUser) {
        final var projectName = request.getProjectName();
        final var collName = request.getCollectionName();
        if (cache.getAdminCollectionEntry(projectName, collName) == null) {
            return new OperationResponse(OperationType.TEST_TRIGGER, "Collection '" + collName + "' not found",
                    ErrorCode.PROJECT_NOT_FOUND);
        }
        if (!configuration.isScriptsEnabled()) {
            return new OperationResponse(OperationType.TEST_TRIGGER, ErrorCode.SCRIPTS_DISABLED);
        }
        final var event = parseEvent(request.getEvent());
        if (event == null) {
            return new OperationResponse(
                    OperationType.TEST_TRIGGER, ErrorCode.INVALID_TRIGGER.getDefaultMessage() + ": unknown event '"
                            + request.getEvent() + "' (expected CREATED, UPDATED or DELETED)",
                    ErrorCode.INVALID_TRIGGER);
        }
        if (request.getDocument() == null) {
            return new OperationResponse(OperationType.TEST_TRIGGER,
                    ErrorCode.INVALID_TRIGGER.getDefaultMessage() + ": a document is required",
                    ErrorCode.INVALID_TRIGGER);
        }
        final var trigger = findByName(cache.getTriggersFor(projectName, collName), request.getName());
        if (trigger == null) {
            return new OperationResponse(OperationType.TEST_TRIGGER,
                    "Trigger '" + request.getName() + "' not found on collection '" + collName + "'",
                    ErrorCode.TRIGGER_NOT_FOUND);
        }
        if (!trigger.isBefore()) {
            return new OperationResponse(OperationType.TEST_TRIGGER,
                    ErrorCode.INVALID_TRIGGER.getDefaultMessage()
                            + ": only a before trigger can be tested, because only it is directly callable",
                    ErrorCode.INVALID_TRIGGER);
        }
        if (!trigger.getEvents().contains(event)) {
            return new OperationResponse(OperationType.TEST_TRIGGER, ErrorCode.INVALID_TRIGGER.getDefaultMessage()
                    + ": trigger '" + trigger.getName() + "' does not watch " + event, ErrorCode.INVALID_TRIGGER);
        }
        final var procedure = cache.getProcedure(projectName, trigger.getProcedureName());
        if (procedure == null || !procedure.isEnabled()) {
            return new OperationResponse(OperationType.TEST_TRIGGER,
                    "Procedure '" + trigger.getProcedureName() + "' not found in project '" + projectName + "'",
                    ErrorCode.PROCEDURE_NOT_FOUND);
        }
        return runTest(request, trigger, event, actingUser);
    }

    private static OperationResponse runTest(TestTriggerRequest request, TriggerDefinition trigger, EventType event,
            String actingUser) {
        final var document = request.getDocument();
        final var id = document.has(Globals.PK_FIELD) && document.get(Globals.PK_FIELD).isJsonString()
                ? document.get(Globals.PK_FIELD).asJsonString().getValue()
                : null;
        try (var hooks = BeforeHookContext.openForTest(request.getProjectName(), request.getCollectionName(), event,
                actingUser, trigger)) {
            final var outcome = hooks.apply(document, id, OperationType.TEST_TRIGGER);
            if (!outcome.isRejected()) {
                final var replaced = outcome.document() != document;
                return new TestTriggerResponse("Trigger tested successfully",
                        replaced ? TestTriggerResponse.DECISION_REPLACE : TestTriggerResponse.DECISION_ACCEPT,
                        outcome.document(), null, hooks.logs(), false, null);
            }
            final var rejection = outcome.rejection();
            if (ErrorCode.BEFORE_HOOK_REJECTED.getCode().equals(rejection.getErrorCode())) {
                return new TestTriggerResponse("Trigger tested successfully", TestTriggerResponse.DECISION_REJECT, null,
                        rejection.getMessage(), hooks.logs(), false, hooks.lastStack());
            }
            return rejection;
        }
    }

    public static OperationResponse executeDelete(DeleteTriggerRequest request, String actingUser) throws IOException {
        final var projectName = request.getProjectName();
        final var collName = request.getCollectionName();
        if (cache.getAdminCollectionEntry(projectName, collName) == null) {
            return new OperationResponse(OperationType.DELETE_TRIGGER, "Collection '" + collName + "' not found",
                    ErrorCode.PROJECT_NOT_FOUND);
        }
        final var remaining = new ArrayList<>(cache.getTriggersFor(projectName, collName));
        final var existing = findByName(remaining, request.getName());
        final var foreign = existing == null
                ? null
                : ProcedureBindingGuard.foreignDefinitionRefusal(OperationType.DELETE_TRIGGER, projectName,
                        existing.getDefiner(), actingUser);
        if (foreign != null) {
            return foreign;
        }
        remaining.removeIf(trigger -> trigger.getName().equals(request.getName()));
        persist(projectName, collName, remaining);
        return OperationResponse.ok(OperationType.DELETE_TRIGGER, "Trigger deleted successfully");
    }

    public static OperationResponse executeList(ListTriggersRequest request) {
        return OperationResponse.respondOrError(OperationType.LIST_TRIGGERS, ErrorCode.ERROR_RETRIEVING,
                () -> listTriggers(request));
    }

    private static OperationResponse listTriggers(ListTriggersRequest request) {
        final var projectName = request.getProjectName();
        if (cache.getAdminProjectEntry(projectName) == null) {
            return new OperationResponse(OperationType.LIST_TRIGGERS, "Project '" + projectName + "' not found",
                    ErrorCode.PROJECT_NOT_FOUND);
        }
        final var collNames = request.getCollectionName() == null || request.getCollectionName().isBlank()
                ? cache.getCollectionNamesForProject(projectName)
                : List.of(request.getCollectionName());
        final var result = new ArrayList<JsonObject>();
        for (final var collName : collNames) {
            for (final var trigger : cache.getTriggersFor(projectName, collName)) {
                final var json = trigger.toJsonObject();
                json.addProperty("collectionName", collName);
                result.add(json);
            }
        }
        return new ListTriggersResponse("Ok", result);
    }

    public static boolean disable(String projectName, String collName, TriggerDefinition dispatched, String reason)
            throws IOException, InterruptedException {
        if (!locks.tryLockWrite(projectName, collName, OperationLocks.lockBudgetMillis(false))) {
            return false;
        }
        try {
            final var current = new ArrayList<>(cache.getTriggersFor(projectName, collName));
            final var existing = findByName(current, dispatched.getName());
            if (existing == null || !existing.isEnabled() || existing.getVersion() != dispatched.getVersion()) {
                return false;
            }
            current.set(current.indexOf(existing), existing.disabledCopy(reason, System.currentTimeMillis()));
            persist(projectName, collName, current);
            return true;
        } finally {
            locks.release(projectName, collName);
        }
    }

    public static List<TriggerDefinition> triggersIn(String projectName) {
        final var result = new ArrayList<TriggerDefinition>();
        for (final var collName : cache.getCollectionNamesForProject(projectName)) {
            result.addAll(cache.getTriggersFor(projectName, collName));
        }
        return result;
    }

    public static String triggerReferencing(String projectName, String procedureName) {
        for (final var trigger : triggersIn(projectName)) {
            if (procedureName.equals(trigger.getProcedureName())) {
                return trigger.getName();
            }
        }
        return null;
    }

    private static void persist(String projectName, String collName, List<TriggerDefinition> definitions)
            throws IOException {
        if (definitions.isEmpty()) {
            if (fs.metadataFiles().deleteTriggers(projectName, collName)) {
                AdminStamp.tombstone(AdminRecordKey.triggers(projectName, collName));
            }
            cache.removeTriggers(projectName, collName);
            return;
        }
        StoredDefinitions.writeTriggers(projectName, collName, TriggerDefinition.toFileJson(definitions));
        cache.putTriggers(projectName, collName, definitions);
    }

    private static TriggerDefinition findByName(List<TriggerDefinition> definitions, String name) {
        for (final var definition : definitions) {
            if (definition.getName().equals(name)) {
                return definition;
            }
        }
        return null;
    }

    private static EventType parseEvent(String name) {
        for (final var type : EventType.values()) {
            if (type.name().equals(name)) {
                return type;
            }
        }
        return null;
    }
}
