package org.ccg.mpp.client;

import java.util.List;
import org.ccg.mpp.ejson.elements.JsonArray;
import org.ccg.mpp.ejson.elements.JsonBoolean;
import org.ccg.mpp.ejson.elements.JsonNumber;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;

public final class Requests {
    public static final String TYPE = "type";
    public static final String PROJECT = "projectName";
    public static final String COLLECTION = "collectionName";
    public static final String NAME = "name";
    public static final String PROCEDURE_NAME = "procedureName";
    public static final String CRON = "cron";
    public static final String INTERVAL_MS = "intervalMs";
    public static final String SNAPSHOT_ID = "snapshotId";
    public static final String COLLECTION_NAMES = "collectionNames";
    private static final String AFTER_ID = "afterId";
    private static final String LIMIT = "limit";

    private Requests() {
    }

    public static JsonObject authenticate(String user, String password) {
        final var request = typed("AUTHENTICATE");
        request.add("username", new JsonString(user));
        request.add("password", new JsonString(password));
        return request;
    }

    public static JsonObject close() {
        return typed("CLOSE_CONNECTION");
    }

    public static JsonObject serverInfo() {
        return typed("SERVER_INFO");
    }

    public static JsonObject listProjects() {
        return typed("LIST_PROJECTS");
    }

    public static JsonObject listCollections(String project) {
        return onProject("LIST_COLLECTIONS", project);
    }

    public static JsonObject stats() {
        return typed("GET_PROJECT_STATS");
    }

    public static JsonObject startSnapshot(String project, List<String> collections) {
        final var request = onProject("START_SNAPSHOT", project);
        final var names = new JsonArray();
        for (final var collection : collections) {
            names.add(new JsonString(collection));
        }
        request.add(COLLECTION_NAMES, names);
        return request;
    }

    public static JsonObject readSnapshot(String project, String collection, String snapshotId, String afterId,
            int limit) {
        final var request = onCollection("READ_SNAPSHOT", project, collection);
        request.add(SNAPSHOT_ID, new JsonString(snapshotId));
        if (afterId != null) {
            request.add(AFTER_ID, new JsonString(afterId));
        }
        request.add(LIMIT, new JsonNumber(limit));
        return request;
    }

    public static JsonObject releaseSnapshot(String project, String snapshotId) {
        final var request = onProject("RELEASE_SNAPSHOT", project);
        request.add(SNAPSHOT_ID, new JsonString(snapshotId));
        return request;
    }

    public static JsonObject getSchema(String project, String collection) {
        return onCollection("GET_SCHEMA", project, collection);
    }

    public static JsonObject listProcedures(String project, boolean includeSource) {
        final var request = onProject("LIST_PROCEDURES", project);
        request.add("includeSource", JsonBoolean.of(includeSource));
        return request;
    }

    public static JsonObject listTriggers(String project) {
        return onProject("LIST_TRIGGERS", project);
    }

    public static JsonObject listSchedules(String project, boolean includeArgs) {
        final var request = onProject("LIST_SCHEDULES", project);
        request.add("includeArgs", JsonBoolean.of(includeArgs));
        return request;
    }

    public static JsonObject listEntrypoints(String project) {
        return onProject("LIST_ENTRYPOINTS", project);
    }

    public static JsonObject listMiddlewares(String project) {
        return onProject("LIST_MIDDLEWARES", project);
    }

    public static JsonObject createProject(String project) {
        return onProject("CREATE_PROJECT", project);
    }

    public static JsonObject createCollection(String project, String collection) {
        return onCollection("CREATE_COLLECTION", project, collection);
    }

    public static JsonObject bulkSave(String project, String collection, JsonArray documents) {
        final var request = onCollection("BULK_SAVE", project, collection);
        request.add("objects", documents);
        return request;
    }

    public static JsonObject saveSchema(String project, String collection, JsonObject schema) {
        final var request = onCollection("SAVE_SCHEMA", project, collection);
        request.add("schema", schema);
        return request;
    }

    public static JsonObject createIndexes(String project, String collection, List<String> fields) {
        final var request = onCollection("CREATE_INDEX", project, collection);
        final var names = new JsonArray();
        for (final var field : fields) {
            names.add(new JsonString(field));
        }
        request.add("fieldNames", names);
        return request;
    }

    public static JsonObject saveProcedureFrom(String project, JsonObject listed) {
        final var request = onProject("SAVE_PROCEDURE", project);
        copy(listed, request, NAME);
        if (listed.has("source")) {
            request.add("script", listed.get("source"));
        }
        copy(listed, request, "description", "enabled");
        return request;
    }

    public static JsonObject saveTriggerFrom(String project, JsonObject listed) {
        final var request = onProject("SAVE_TRIGGER", project);
        copy(listed, request, COLLECTION, NAME, "events", PROCEDURE_NAME, "mode", "timing", "allowCascade", "enabled");
        return request;
    }

    public static JsonObject saveScheduleFrom(String project, JsonObject listed) {
        final var request = onProject("SAVE_SCHEDULE", project);
        copy(listed, request, NAME, PROCEDURE_NAME);
        final var cron = listed.get(CRON);
        if (cron != null && cron.isJsonString() && !cron.asJsonString().getValue().isBlank()) {
            request.add(CRON, cron);
        } else {
            copy(listed, request, INTERVAL_MS);
        }
        copy(listed, request, "args", "timeoutMs", "description", "enabled");
        return request;
    }

    public static JsonObject saveEntrypointFrom(String project, JsonObject listed) {
        final var request = onProject("SAVE_ENTRYPOINT", project);
        copy(listed, request, NAME, "method", "path", PROCEDURE_NAME, "timeoutMs", "description", "enabled");
        return request;
    }

    public static JsonObject saveMiddlewareFrom(String project, JsonObject listed) {
        final var request = onProject("SAVE_MIDDLEWARE", project);
        copy(listed, request, NAME, "phase", "order", "match", PROCEDURE_NAME, "timeoutMs", "description");
        if (!listed.has("disabledReason")) {
            copy(listed, request, "enabled");
        }
        return request;
    }

    private static void copy(JsonObject from, JsonObject to, String... fields) {
        for (final var field : fields) {
            final var value = from.get(field);
            if (value != null && !value.isJsonNull()) {
                to.add(field, value);
            }
        }
    }

    private static JsonObject typed(String type) {
        final var request = new JsonObject();
        request.add(TYPE, new JsonString(type));
        return request;
    }

    private static JsonObject onProject(String type, String project) {
        final var request = typed(type);
        request.add(PROJECT, new JsonString(project));
        return request;
    }

    private static JsonObject onCollection(String type, String project, String collection) {
        final var request = onProject(type, project);
        request.add(COLLECTION, new JsonString(collection));
        return request;
    }
}
