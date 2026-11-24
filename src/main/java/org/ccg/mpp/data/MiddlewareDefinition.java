package org.ccg.mpp.data;

import static org.ccg.mpp.data.JsonFieldReader.longOrZero;
import static org.ccg.mpp.data.JsonFieldReader.stringOrNull;

import java.util.Objects;
import org.ccg.mpp.ejson.elements.JsonBoolean;
import org.ccg.mpp.ejson.elements.JsonNumber;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;

public class MiddlewareDefinition extends StoredDefinition {
    private static final String PHASE_FIELD = "phase";
    private static final String ORDER_FIELD = "order";
    private static final String MATCH_FIELD = "match";
    private static final String PROCEDURE_NAME_FIELD = "procedureName";
    private static final String TIMEOUT_MS_FIELD = "timeoutMs";
    private static final String DEFINER_FIELD = "definer";
    private static final String DESCRIPTION_FIELD = "description";

    private MiddlewarePhase phase;
    private int order;
    private MiddlewareMatch match = MiddlewareMatch.EVERYTHING;
    private String procedureName;
    private long timeoutMs;
    private String definer;
    private String description;

    public MiddlewareDefinition() {
    }

    public MiddlewareDefinition(String name, MiddlewarePhase phase, int order, MiddlewareMatch match,
            String procedureName, long timeoutMs, boolean enabled, String definer, String description) {
        this.name = name;
        this.phase = phase;
        this.order = order;
        this.match = match == null ? MiddlewareMatch.EVERYTHING : match;
        this.procedureName = procedureName;
        this.timeoutMs = timeoutMs;
        this.enabled = enabled;
        this.definer = definer;
        this.description = description;
    }

    public MiddlewareDefinition stamped(long version, long createdAt, long updatedAt, String updatedBy) {
        final var copy = fromJsonObject(toJsonObject());
        copy.version = version;
        copy.createdAt = createdAt;
        copy.updatedAt = updatedAt;
        copy.updatedBy = updatedBy;
        return copy;
    }

    public static MiddlewareDefinition fromJsonObject(JsonObject object) {
        final var result = new MiddlewareDefinition();
        result.readCommonFields(object);
        result.phase = MiddlewarePhase.parse(stringOrNull(object, PHASE_FIELD));
        result.order = (int) longOrZero(object, ORDER_FIELD);
        final var match = object.get(MATCH_FIELD);
        result.match = match == null || !match.isJsonObject()
                ? MiddlewareMatch.EVERYTHING
                : MiddlewareMatch.fromJsonObject(match.asJsonObject());
        result.procedureName = stringOrNull(object, PROCEDURE_NAME_FIELD);
        result.timeoutMs = longOrZero(object, TIMEOUT_MS_FIELD);
        result.definer = stringOrNull(object, DEFINER_FIELD);
        result.description = stringOrNull(object, DESCRIPTION_FIELD);
        return result;
    }

    public JsonObject toJsonObject() {
        final var json = new JsonObject();
        json.add(NAME_FIELD, new JsonString(name));
        json.add(PHASE_FIELD, new JsonString(phase.name()));
        json.add(ORDER_FIELD, new JsonNumber(order));
        if (!match.isEverything()) {
            json.add(MATCH_FIELD, match.toJsonObject());
        }
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

    public MiddlewareDefinition disabledCopy(String reason, long at) {
        final var copy = fromJsonObject(toJsonObject());
        copy.markDisabled(reason, at);
        return copy;
    }

    public boolean isSkipped() {
        return !enabled && disabledReason == null;
    }

    public boolean isRevoked() {
        return !enabled && disabledReason != null;
    }

    public MiddlewarePhase getPhase() {
        return phase;
    }

    public int getOrder() {
        return order;
    }

    public MiddlewareMatch getMatch() {
        return match;
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
        if (!(o instanceof MiddlewareDefinition that)) {
            return false;
        }
        return order == that.order && timeoutMs == that.timeoutMs && enabled == that.enabled && version == that.version
                && createdAt == that.createdAt && updatedAt == that.updatedAt && phase == that.phase
                && Objects.equals(name, that.name) && Objects.equals(match, that.match)
                && Objects.equals(procedureName, that.procedureName) && Objects.equals(definer, that.definer)
                && Objects.equals(description, that.description) && Objects.equals(updatedBy, that.updatedBy)
                && Objects.equals(disabledReason, that.disabledReason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, phase, order, match, procedureName, timeoutMs, enabled, definer, description, version,
                createdAt, updatedAt, updatedBy, disabledReason);
    }

    @Override
    public String toString() {
        return "MiddlewareDefinition(name=" + name + ", phase=" + phase + ", order=" + order + ", procedure="
                + procedureName + ", enabled=" + enabled + ", definer=" + definer + ", version=" + version + ")";
    }
}
