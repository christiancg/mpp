package io.github.christiancg.mpp.client.internal;

import io.github.christiancg.mpp.client.Document;
import io.github.christiancg.mpp.client.admin.ScriptRun;
import io.github.christiancg.mpp.client.admin.ServerInfo;
import io.github.christiancg.mpp.client.admin.TransactionInfo;
import io.github.christiancg.mpp.client.admin.TriggerRun;
import io.github.christiancg.mpp.client.admin.UserInfo;
import io.github.christiancg.mpp.client.scripting.EntrypointInfo;
import io.github.christiancg.mpp.client.scripting.EntrypointMethod;
import io.github.christiancg.mpp.client.scripting.MiddlewareCriteria;
import io.github.christiancg.mpp.client.scripting.MiddlewareInfo;
import io.github.christiancg.mpp.client.scripting.MiddlewareMatch;
import io.github.christiancg.mpp.client.scripting.ProcedureInfo;
import io.github.christiancg.mpp.client.scripting.ScheduleInfo;
import io.github.christiancg.mpp.client.scripting.TriggerInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import tools.jackson.databind.node.ObjectNode;

public final class Descriptors {
    private static final String NAME = "name";

    private Descriptors() {
    }

    public static ProcedureInfo procedure(Document row) {
        return new ProcedureInfo(text(row, NAME), whole(row, "version"), flag(row, "enabled", true),
                row.getString("description"), row.getString("source"), row);
    }

    public static TriggerInfo trigger(Document row) {
        return new TriggerInfo(text(row, NAME), row.getString("collection"), strings(row, "events"),
                text(row, "procedureName"), row.getString("timing").orElse("after"),
                row.getString("mode").orElse("document"), flag(row, "enabled", true), whole(row, "version"),
                row.getString("definer"), row);
    }

    public static ScheduleInfo schedule(Document row) {
        return new ScheduleInfo(text(row, NAME), text(row, "procedureName"), row.getString("cron"),
                optionalWhole(row, "intervalMs"), flag(row, "enabled", true), whole(row, "version"),
                row.getString("definer"), optionalWhole(row, "nextRunAt"), row);
    }

    public static EntrypointInfo entrypoint(Document row) {
        return new EntrypointInfo(text(row, NAME), text(row, "method"), text(row, "path"), text(row, "url"),
                text(row, "procedureName"), whole(row, "timeoutMs"), flag(row, "enabled", true), whole(row, "version"),
                row.getString("definer"), row.getString("description"), row.getString("disabledReason"), row);
    }

    public static MiddlewareInfo middleware(Document row) {
        return new MiddlewareInfo(text(row, NAME), text(row, "phase"), whole(row, "order"),
                row.getDocument("match").map(Descriptors::match), text(row, "procedureName"), whole(row, "timeoutMs"),
                flag(row, "enabled", true), whole(row, "version"), row.getString("definer"),
                row.getString("description"), row.getString("disabledReason"), row);
    }

    public static UserInfo user(Document row) {
        return new UserInfo(text(row, Document.ID_FIELD), flag(row, "admin", false), strings(row, "globalPermissions"),
                grants(row, "projectPermissions"), grants(row, "collectionPermissions"),
                grants(row, "scriptPermissions"), strings(row, "ownedProjects"), row);
    }

    public static TransactionInfo transaction(Document row) {
        return new TransactionInfo(text(row, "dtxId"), row.getString("coordinator"),
                flag(row, "coordinatorReachable", false), strings(row, "participants"), whole(row, "ageMs"), row);
    }

    public static ScriptRun scriptRun(Document row) {
        return new ScriptRun(text(row, "runId"), row.getString("node"), text(row, "kind"), row.getString("project"),
                row.getString(NAME), row.getString("username"), whole(row, "ageMs"), row);
    }

    public static TriggerRun triggerRun(Document row) {
        return new TriggerRun(text(row, "runId"), text(row, "status"), row.getString("project"),
                row.getString("collection"), row.getString("trigger"), row.getString("procedure"),
                row.getString("event"), whole(row, "attempts"), row.getString("lastError"), row);
    }

    public static ServerInfo server(Document row) {
        return new ServerInfo(row.getString("state"), row.getString("version"), row);
    }

    private static MiddlewareMatch match(Document match) {
        final var exclude = new ArrayList<MiddlewareCriteria>();
        Documents.node(match).path("exclude").values().forEach(rule -> {
            if (rule instanceof ObjectNode object) {
                exclude.add(criteria(Documents.wrap(object)));
            }
        });
        return new MiddlewareMatch(criteria(match), exclude);
    }

    private static MiddlewareCriteria criteria(Document criteria) {
        return new MiddlewareCriteria(knownMethods(strings(criteria, "methods")), criteria.getString("pathPrefix"),
                strings(criteria, "entrypoints"));
    }

    private static List<EntrypointMethod> knownMethods(List<String> names) {
        return Arrays.stream(EntrypointMethod.values()).filter(method -> names.contains(method.name())).toList();
    }

    private static String text(Document row, String field) {
        return row.getString(field).orElse("");
    }

    private static long whole(Document row, String field) {
        return row.getLong(field).orElse(0);
    }

    private static OptionalLong optionalWhole(Document row, String field) {
        return row.getLong(field);
    }

    private static boolean flag(Document row, String field, boolean fallback) {
        return row.getBoolean(field).orElse(fallback);
    }

    private static List<String> strings(Document row, String field) {
        return row.getList(field).map(items -> items.stream().map(String::valueOf).toList()).orElse(List.of());
    }

    private static Map<String, Object> grants(Document row, String field) {
        return row.getDocument(field).map(Document::toMap).orElse(Map.of());
    }
}
