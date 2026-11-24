package org.ccg.mpp.data;

import java.util.ArrayList;
import java.util.List;
import org.ccg.mpp.ejson.elements.JsonArray;
import org.ccg.mpp.ejson.elements.JsonObject;

public record MiddlewareMatch(MiddlewareCriteria include, List<MiddlewareCriteria> exclude) {
    public static final MiddlewareMatch EVERYTHING = new MiddlewareMatch(MiddlewareCriteria.ANY, List.of());
    private static final String EXCLUDE_FIELD = "exclude";

    public MiddlewareMatch {
        include = include == null ? MiddlewareCriteria.ANY : include;
        exclude = exclude == null ? List.of() : List.copyOf(exclude);
    }

    public boolean wraps(EntrypointDefinition entrypoint) {
        return include.holdsFor(entrypoint) && exclude.stream().noneMatch(rule -> rule.holdsFor(entrypoint));
    }

    public boolean isEverything() {
        return include.isUnconstrained() && exclude.isEmpty();
    }

    public static MiddlewareMatch fromJsonObject(JsonObject object) {
        if (object == null) {
            return EVERYTHING;
        }
        final var rules = new ArrayList<MiddlewareCriteria>();
        final var excluded = object.get(EXCLUDE_FIELD);
        if (excluded != null && excluded.isJsonArray()) {
            for (final var rule : excluded.asJsonArray()) {
                rules.add(MiddlewareCriteria.fromJsonObject(rule.asJsonObject()));
            }
        }
        return new MiddlewareMatch(MiddlewareCriteria.fromJsonObject(object), rules);
    }

    public JsonObject toJsonObject() {
        final var json = new JsonObject();
        include.writeTo(json);
        if (!exclude.isEmpty()) {
            final var rules = new JsonArray();
            exclude.forEach(rule -> rules.add(rule.toJsonObject()));
            json.add(EXCLUDE_FIELD, rules);
        }
        return json;
    }
}
