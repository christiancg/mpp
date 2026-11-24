package org.ccg.mpp.data;

import static org.ccg.mpp.data.JsonFieldReader.stringOrNull;

import java.util.ArrayList;
import java.util.List;
import org.ccg.mpp.ejson.elements.JsonArray;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;

public record MiddlewareCriteria(List<String> methods, String pathPrefix, List<String> entrypoints) {
    public static final MiddlewareCriteria ANY = new MiddlewareCriteria(List.of(), null, List.of());
    private static final String METHODS_FIELD = "methods";
    private static final String PATH_PREFIX_FIELD = "pathPrefix";
    private static final String ENTRYPOINTS_FIELD = "entrypoints";
    private static final String ROOT = "/";

    public MiddlewareCriteria {
        methods = methods == null ? List.of() : List.copyOf(methods);
        entrypoints = entrypoints == null ? List.of() : List.copyOf(entrypoints);
    }

    public boolean holdsFor(EntrypointDefinition entrypoint) {
        return (methods.isEmpty() || methods.contains(entrypoint.getMethod()))
                && (entrypoints.isEmpty() || entrypoints.contains(entrypoint.getName()))
                && prefixCovers(entrypoint.getPath());
    }

    public boolean isUnconstrained() {
        return methods.isEmpty() && pathPrefix == null && entrypoints.isEmpty();
    }

    private boolean prefixCovers(String templatePath) {
        if (pathPrefix() == null || ROOT.equals(pathPrefix())) {
            return true;
        }
        if (templatePath == null) {
            return false;
        }
        final var prefixSegments = segmentsOf(pathPrefix);
        final var templateSegments = segmentsOf(templatePath);
        if (prefixSegments.length > templateSegments.length) {
            return false;
        }
        for (var i = 0; i < prefixSegments.length; i++) {
            if (!prefixSegments[i].equals(templateSegments[i])) {
                return false;
            }
        }
        return true;
    }

    private static String[] segmentsOf(String path) {
        return ROOT.equals(path) ? new String[0] : path.substring(1).split(ROOT, -1);
    }

    public static MiddlewareCriteria fromJsonObject(JsonObject object) {
        if (object == null) {
            return ANY;
        }
        return new MiddlewareCriteria(stringsOf(object, METHODS_FIELD), stringOrNull(object, PATH_PREFIX_FIELD),
                stringsOf(object, ENTRYPOINTS_FIELD));
    }

    public void writeTo(JsonObject json) {
        if (!methods.isEmpty()) {
            json.add(METHODS_FIELD, arrayOf(methods));
        }
        if (pathPrefix() != null) {
            json.add(PATH_PREFIX_FIELD, new JsonString(pathPrefix()));
        }
        if (!entrypoints.isEmpty()) {
            json.add(ENTRYPOINTS_FIELD, arrayOf(entrypoints));
        }
    }

    public JsonObject toJsonObject() {
        final var json = new JsonObject();
        writeTo(json);
        return json;
    }

    private static List<String> stringsOf(JsonObject object, String field) {
        final var value = object.get(field);
        if (value == null || !value.isJsonArray()) {
            return List.of();
        }
        final var result = new ArrayList<String>();
        for (final var element : value.asJsonArray()) {
            result.add(element.asJsonString().getValue());
        }
        return result;
    }

    private static JsonArray arrayOf(List<String> values) {
        final var array = new JsonArray();
        values.forEach(array::add);
        return array;
    }
}
