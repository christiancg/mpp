package org.ccg.mpp.data;

import static org.ccg.mpp.data.JsonFieldReader.longOrZero;
import static org.ccg.mpp.data.JsonFieldReader.stringOrNull;

import java.util.Objects;
import org.ccg.mpp.ejson.elements.JsonBoolean;
import org.ccg.mpp.ejson.elements.JsonNumber;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;

public class EntrypointDefinition extends StoredDefinition {
    private static final String METHOD_FIELD = "method";
    private static final String PATH_FIELD = "path";
    private static final String PROCEDURE_NAME_FIELD = "procedureName";
    private static final String TIMEOUT_MS_FIELD = "timeoutMs";
    private static final String DEFINER_FIELD = "definer";
    private static final String DESCRIPTION_FIELD = "description";

    private String method;
    private String path;
    private String procedureName;
    private long timeoutMs;
    private String definer;
    private String description;

    public EntrypointDefinition() {
    }

    public EntrypointDefinition(String name, String method, String path, String procedureName, long timeoutMs,
            boolean enabled, String definer, String description, long version, long createdAt, long updatedAt,
            String updatedBy) {
        this.name = name;
        this.method = method;
        this.path = path;
        this.procedureName = procedureName;
        this.timeoutMs = timeoutMs;
        this.enabled = enabled;
        this.definer = definer;
        this.description = description;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
    }

    public static EntrypointDefinition fromJsonObject(JsonObject object) {
        final var result = new EntrypointDefinition();
        result.readCommonFields(object);
        result.method = stringOrNull(object, METHOD_FIELD);
        result.path = stringOrNull(object, PATH_FIELD);
        result.procedureName = stringOrNull(object, PROCEDURE_NAME_FIELD);
        result.timeoutMs = longOrZero(object, TIMEOUT_MS_FIELD);
        result.definer = stringOrNull(object, DEFINER_FIELD);
        result.description = stringOrNull(object, DESCRIPTION_FIELD);
        return result;
    }

    public JsonObject toJsonObject() {
        final var json = new JsonObject();
        json.add(NAME_FIELD, new JsonString(name));
        json.add(METHOD_FIELD, new JsonString(method));
        json.add(PATH_FIELD, new JsonString(path));
        json.add(PROCEDURE_NAME_FIELD, new JsonString(procedureName));
        json.add(TIMEOUT_MS_FIELD, new JsonNumber(timeoutMs));
        json.add(ENABLED_FIELD, JsonBoolean.of(enabled));
        if (definer != null) {
            json.add(DEFINER_FIELD, new JsonString(definer));
        }
        if (description != null) {
            json.add(DESCRIPTION_FIELD, new JsonString(description));
        }
        json.add(VERSION_FIELD, new JsonNumber(version));
        writeAuditFields(json);
        return json;
    }

    public EntrypointDefinition disabledCopy(String reason, long at) {
        final var copy = fromJsonObject(toJsonObject());
        copy.markDisabled(reason, at);
        return copy;
    }

    public String getMethod() {
        return method;
    }

    public String getPath() {
        return path;
    }

    public String getProcedureName() {
        return procedureName;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public String getDefiner() {
        return definer;
    }

    public String getDescription() {
        return description;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EntrypointDefinition that)) {
            return false;
        }
        return timeoutMs == that.timeoutMs && enabled == that.enabled && version == that.version
                && createdAt == that.createdAt && updatedAt == that.updatedAt && Objects.equals(name, that.name)
                && Objects.equals(method, that.method) && Objects.equals(path, that.path)
                && Objects.equals(procedureName, that.procedureName) && Objects.equals(definer, that.definer)
                && Objects.equals(description, that.description) && Objects.equals(updatedBy, that.updatedBy)
                && Objects.equals(disabledReason, that.disabledReason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, method, path, procedureName, timeoutMs, enabled, definer, description, version,
                createdAt, updatedAt, updatedBy, disabledReason);
    }

    @Override
    public String toString() {
        return "EntrypointDefinition(name=" + name + ", method=" + method + ", path=" + path + ", procedure="
                + procedureName + ", enabled=" + enabled + ", definer=" + definer + ", version=" + version + ")";
    }
}
