package org.ccg.mpp.client;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.ejson.elements.JsonObject;

public final class DumpScope {
    public record ProjectPlan(String name, List<String> collections, boolean whole) {
        public boolean keepsTrigger(JsonObject listed) {
            return collections.contains(Connection.stringOf(listed, Requests.COLLECTION));
        }
    }

    private static final String FORBIDDEN = "FORBIDDEN";
    private static final String COLLECTIONS = "collections";

    private final List<ProjectPlan> projects;
    private final Map<String, List<String>> indexes;

    private DumpScope(List<ProjectPlan> projects, Map<String, List<String>> indexes) {
        this.projects = projects;
        this.indexes = indexes;
    }

    public static DumpScope plan(Connection connection, ClientOptions options, PrintStream err) {
        final var projects = options.collections().isEmpty()
                ? wholeProjects(connection, options.projects(), err)
                : namedCollections(connection, options.collections());
        return new DumpScope(projects, indexesOf(connection.sendOk(Requests.stats())));
    }

    public List<ProjectPlan> projects() {
        return projects;
    }

    public List<String> indexesOf(String project, String collection) {
        return indexes.getOrDefault(project + "." + collection, List.of());
    }

    private static List<ProjectPlan> wholeProjects(Connection connection, List<String> filter, PrintStream err) {
        final var existing = stringsOf(connection.sendOk(Requests.listProjects()), "projects");
        for (final var name : filter) {
            if (!existing.contains(name)) {
                throw new ClientException("project '" + name + "' not found", ClientException.FAILED);
            }
        }
        final var plans = new ArrayList<ProjectPlan>();
        for (final var name : filter.isEmpty() ? existing : filter) {
            final var request = Requests.listCollections(name);
            final var reply = connection.send(request);
            if (filter.isEmpty() && FORBIDDEN.equals(Connection.stringOf(reply, Connection.STATUS))) {
                err.println("note: " + name + " was not dumped: " + Connection.stringOf(reply, Connection.MESSAGE));
                continue;
            }
            if (!Connection.isOk(reply)) {
                throw Connection.refused(request, reply);
            }
            final var collections = new ArrayList<>(stringsOf(reply, COLLECTIONS));
            collections.remove(Globals.SCRIPT_RUNS_COLLECTION_NAME);
            plans.add(new ProjectPlan(name, List.copyOf(collections), true));
        }
        return plans;
    }

    private static List<ProjectPlan> namedCollections(Connection connection, List<ClientOptions.CollectionRef> refs) {
        final var grouped = new LinkedHashMap<String, LinkedHashSet<String>>();
        for (final var ref : refs) {
            grouped.computeIfAbsent(ref.project(), ignored -> new LinkedHashSet<>()).add(ref.collection());
        }
        final var plans = new ArrayList<ProjectPlan>();
        for (final var entry : grouped.entrySet()) {
            final var existing = collectionsOf(connection, entry.getKey());
            for (final var collection : entry.getValue()) {
                if (!existing.contains(collection) || Globals.SCRIPT_RUNS_COLLECTION_NAME.equals(collection)) {
                    throw new ClientException("collection '" + entry.getKey() + "." + collection + "' not found",
                            ClientException.FAILED);
                }
            }
            plans.add(new ProjectPlan(entry.getKey(), List.copyOf(entry.getValue()), false));
        }
        return plans;
    }

    private static List<String> collectionsOf(Connection connection, String project) {
        return stringsOf(connection.sendOk(Requests.listCollections(project)), COLLECTIONS);
    }

    private static Map<String, List<String>> indexesOf(JsonObject statsReply) {
        final var found = new HashMap<String, List<String>>();
        final var statsElement = statsReply.get("stats");
        if (statsElement == null || !statsElement.isJsonObject()) {
            throw unexpectedReply("stats");
        }
        final var stats = statsElement.asJsonObject();
        for (final var project : objectsOf(stats, "projects")) {
            final var projectName = Connection.stringOf(project, Requests.NAME);
            for (final var collection : objectsOf(project, COLLECTIONS)) {
                found.put(projectName + "." + Connection.stringOf(collection, Requests.NAME),
                        stringsOf(collection, "indexes"));
            }
        }
        return found;
    }

    public static List<String> stringsOf(JsonObject object, String field) {
        final var strings = new ArrayList<String>();
        final var array = object.get(field);
        if (array != null && array.isJsonArray()) {
            for (final var element : array.asJsonArray()) {
                if (!element.isJsonString()) {
                    throw unexpectedReply(field);
                }
                strings.add(element.asJsonString().getValue());
            }
        }
        return strings;
    }

    public static List<JsonObject> objectsOf(JsonObject object, String field) {
        final var objects = new ArrayList<JsonObject>();
        final var array = object.get(field);
        if (array != null && array.isJsonArray()) {
            for (final var element : array.asJsonArray()) {
                if (!element.isJsonObject()) {
                    throw unexpectedReply(field);
                }
                objects.add(element.asJsonObject());
            }
        }
        return objects;
    }

    static ClientException unexpectedReply(String field) {
        return new ClientException("the server's reply has an unexpected " + field, ClientException.FAILED);
    }
}
