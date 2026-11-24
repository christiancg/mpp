package org.ccg.mpp.simplejs.builtins;

import java.util.List;
import java.util.Set;
import org.ccg.mpp.data.MiddlewarePhase;
import org.ccg.mpp.ejson.elements.JsonBaseElement;
import org.ccg.mpp.ejson.elements.JsonNull;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.simplejs.exceptions.TypeErrorException;
import org.ccg.mpp.simplejs.host.MiddlewareDecision;
import org.ccg.mpp.simplejs.values.EJsonInterop;
import org.ccg.mpp.simplejs.values.JsNativeFunction;
import org.ccg.mpp.simplejs.values.JsNull;
import org.ccg.mpp.simplejs.values.JsObject;
import org.ccg.mpp.simplejs.values.JsUndefined;
import org.ccg.mpp.simplejs.values.JsValue;

public final class MiddlewareModule {
    public static final String HEADERS = "headers";
    public static final String QUERY = "query";
    public static final String PATH_PARAMS = "pathParams";
    public static final String BODY = "body";
    public static final String LOCALS = "locals";
    public static final String STATUS = "status";
    private static final Set<String> INGRESS_KEYS = Set.of(HEADERS, QUERY, PATH_PARAMS, BODY, LOCALS);
    private static final Set<String> EGRESS_KEYS = Set.of(STATUS, HEADERS, BODY, LOCALS);
    private static final int LOWEST_STATUS = 100;
    private static final int HIGHEST_STATUS = 599;
    private static final String ALREADY_DECIDED = "next() or reject() was already called in this middleware run";

    private MiddlewareModule() {
    }

    public static JsObject create(MiddlewareDecision decision, InterpreterOps ops) {
        final var module = new JsObject();
        module.set("next", new JsNativeFunction("next", (_, args) -> {
            decide(decision, new MiddlewareDecision.Outcome.Next(changesOf(decision, argument(args, 0), ops)));
            return JsUndefined.getInstance();
        }));
        module.set("reject", new JsNativeFunction("reject", (_, args) -> {
            final var status = statusOf(EJsonInterop.toHostPayload(argument(args, 0), ops), "reject()");
            final var converted = EJsonInterop.toHostPayload(argument(args, 1), ops);
            final var body = converted == null ? JsonNull.INSTANCE : converted;
            final var headers = optionalObject(EJsonInterop.toHostPayload(argument(args, 2), ops), "reject() headers");
            requireHeaderValues(headers, "reject() headers", false);
            decide(decision, new MiddlewareDecision.Outcome.Reject(status, body, headers));
            return JsUndefined.getInstance();
        }));
        return module;
    }

    private static JsValue argument(List<JsValue> args, int index) {
        return index < args.size() ? args.get(index) : JsUndefined.getInstance();
    }

    private static void decide(MiddlewareDecision decision, MiddlewareDecision.Outcome outcome) {
        try {
            decision.decide(outcome);
        } catch (IllegalStateException alreadyDecided) {
            throw new TypeErrorException(ALREADY_DECIDED);
        }
    }

    private static JsonObject changesOf(MiddlewareDecision decision, JsValue value, InterpreterOps ops) {
        if (value instanceof JsUndefined || value instanceof JsNull) {
            return new JsonObject();
        }
        final var changes = optionalObject(EJsonInterop.toHostPayload(value, ops), "next() changes");
        final var ingress = decision.phase() == MiddlewarePhase.INGRESS;
        final var allowed = ingress ? INGRESS_KEYS : EGRESS_KEYS;
        for (final var entry : changes.entrySet()) {
            if (!allowed.contains(entry.getKey())) {
                throw new TypeErrorException(
                        "next() does not accept '" + entry.getKey() + "'; an " + (ingress ? "ingress" : "egress")
                                + " middleware can change " + String.join(", ", allowed.stream().sorted().toList()));
            }
        }
        requireHeaderValues(optionalObject(changes.get(HEADERS), "next() headers"), "next() headers", true);
        requireQueryValues(optionalObject(changes.get(QUERY), "next() query"));
        requirePathParams(optionalObject(changes.get(PATH_PARAMS), "next() pathParams"), decision.declaredPathParams());
        optionalObject(changes.get(LOCALS), "next() locals");
        if (changes.has(STATUS)) {
            statusOf(changes.get(STATUS), "next() status");
        }
        return changes;
    }

    private static JsonObject optionalObject(JsonBaseElement value, String what) {
        if (value == null || value.isJsonNull()) {
            return new JsonObject();
        }
        if (!value.isJsonObject()) {
            throw new TypeErrorException(what + " must be an object");
        }
        return value.asJsonObject();
    }

    private static int statusOf(JsonBaseElement value, String what) {
        if (value == null || !value.isJsonNumber()) {
            throw new TypeErrorException(what + " needs an integer status from 100 to 599");
        }
        final var number = value.asJsonNumber().doubleValue();
        if (number != Math.rint(number) || number < LOWEST_STATUS || number > HIGHEST_STATUS) {
            throw new TypeErrorException(what + " needs an integer status from 100 to 599, not " + number);
        }
        return (int) number;
    }

    private static void requireHeaderValues(JsonObject headers, String what, boolean nullRemoves) {
        for (final var entry : headers.entrySet()) {
            final var value = entry.getValue();
            if (value.isJsonString() || nullRemoves && value.isJsonNull()) {
                continue;
            }
            throw new TypeErrorException(what + ": the value of '" + entry.getKey() + "' must be a string"
                    + (nullRemoves ? " or null" : ""));
        }
    }

    private static void requireQueryValues(JsonObject query) {
        for (final var entry : query.entrySet()) {
            final var value = entry.getValue();
            if (value.isJsonNull() || value.isJsonString() || isStringArray(value)) {
                continue;
            }
            throw new TypeErrorException("next() query: the value of '" + entry.getKey()
                    + "' must be a string, an array of strings or null");
        }
    }

    private static boolean isStringArray(JsonBaseElement value) {
        if (!value.isJsonArray()) {
            return false;
        }
        for (final var item : value.asJsonArray()) {
            if (!item.isJsonString()) {
                return false;
            }
        }
        return true;
    }

    private static void requirePathParams(JsonObject pathParams, Set<String> declared) {
        for (final var entry : pathParams.entrySet()) {
            if (!declared.contains(entry.getKey())) {
                throw new TypeErrorException(
                        "next() pathParams: the route declares no parameter '" + entry.getKey() + "'");
            }
            if (!entry.getValue().isJsonString()) {
                throw new TypeErrorException(
                        "next() pathParams: the value of '" + entry.getKey() + "' must be a string");
            }
        }
    }
}
