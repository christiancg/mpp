package org.ccg.mpp.ops.entrypoint;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.ccg.mpp.ejson.EJson;
import org.ccg.mpp.ejson.elements.JsonBaseElement;
import org.ccg.mpp.ejson.elements.JsonNull;
import org.ccg.mpp.ejson.elements.JsonNumber;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.http.HttpResponse;
import org.ccg.mpp.ioc.IocContainer;

public record ReplyEnvelope(int status, Map<String, String> headers, JsonBaseElement body) {
    public static final String STATUS = "status";
    public static final String HEADERS = "headers";
    public static final String BODY = "body";
    private static final EJson eJson = IocContainer.get(EJson.class);
    private static final int INTERNAL_ERROR = 500;
    private static final Set<String> SERVER_OWNED_HEADERS = Set.of("content-length", "transfer-encoding", "connection",
            "keep-alive");
    private static final boolean[] TOKEN_CHARS = tokenChars();

    public ReplyEnvelope {
        headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        body = body == null ? JsonNull.INSTANCE : body;
    }

    private static boolean[] tokenChars() {
        final var allowed = new boolean[128];
        for (var c = '0'; c <= '9'; c++) {
            allowed[c] = true;
        }
        for (var c = 'a'; c <= 'z'; c++) {
            allowed[c] = true;
            allowed[Character.toUpperCase(c)] = true;
        }
        for (final var c : "!#$%&'*+-.^_`|~".toCharArray()) {
            allowed[c] = true;
        }
        return allowed;
    }

    public static ReplyEnvelope failure(String runId) {
        final var json = new JsonObject();
        json.addProperty("error", "Internal Server Error");
        if (runId != null) {
            json.addProperty("runId", runId);
        }
        return new ReplyEnvelope(INTERNAL_ERROR, Map.of(), json);
    }

    public static ReplyEnvelope of(int status, JsonObject headers, JsonBaseElement body)
            throws InvalidEnvelopeException {
        final var merged = new LinkedHashMap<String, String>();
        if (headers != null) {
            mergeHeaders(merged, headers, false);
        }
        return new ReplyEnvelope(status, merged, body);
    }

    public ReplyEnvelope withChanges(JsonObject changes) throws InvalidEnvelopeException {
        final var merged = new LinkedHashMap<>(headers);
        final var declared = changes.get(HEADERS);
        if (declared != null && !declared.isJsonNull()) {
            if (!declared.isJsonObject()) {
                throw new InvalidEnvelopeException("'headers' must be an object");
            }
            mergeHeaders(merged, declared.asJsonObject(), true);
        }
        final var newStatus = changes.has(STATUS) ? (int) changes.get(STATUS).asJsonNumber().doubleValue() : status;
        final var newBody = changes.has(BODY) ? changes.get(BODY) : body;
        return new ReplyEnvelope(newStatus, merged, newBody);
    }

    private static void mergeHeaders(Map<String, String> target, JsonObject declared, boolean nullRemoves)
            throws InvalidEnvelopeException {
        for (final var header : declared.entrySet()) {
            final var name = header.getKey();
            if (!isToken(name)) {
                throw new InvalidEnvelopeException("the header name '" + name + "' is not an HTTP token");
            }
            if (SERVER_OWNED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            target.keySet().removeIf(name::equalsIgnoreCase);
            final var value = header.getValue();
            if (nullRemoves && value.isJsonNull()) {
                continue;
            }
            target.put(name, headerText(name, value));
        }
    }

    private static String headerText(String name, JsonBaseElement value) throws InvalidEnvelopeException {
        final String text;
        if (value.isJsonString()) {
            text = value.asJsonString().getValue();
        } else if (value.isJsonNumber() || value.isJsonBoolean()) {
            text = eJson.toJson(value);
        } else {
            throw new InvalidEnvelopeException("the header '" + name + "' must be a string, number or boolean");
        }
        if (!isFieldValue(text)) {
            throw new InvalidEnvelopeException("the header '" + name + "' holds a control or non-Latin-1 character");
        }
        return text;
    }

    public JsonObject toArgs() {
        final var json = new JsonObject();
        json.add(STATUS, new JsonNumber(status));
        final var headerObject = new JsonObject();
        headers.forEach((name, value) -> headerObject.add(name.toLowerCase(Locale.ROOT), new JsonString(value)));
        json.add(HEADERS, headerObject);
        json.add(BODY, body);
        return json;
    }

    public HttpResponse toHttpResponse() {
        final var responseHeaders = new LinkedHashMap<>(headers);
        if (body.isJsonNull()) {
            return new HttpResponse(status, responseHeaders, null, false);
        }
        final byte[] bytes;
        final String contentType;
        if (body.isJsonString()) {
            bytes = body.asJsonString().getValue().getBytes(StandardCharsets.UTF_8);
            contentType = HttpResponse.PLAIN_TEXT;
        } else {
            bytes = eJson.toJson(body).getBytes(StandardCharsets.UTF_8);
            contentType = HttpResponse.JSON;
        }
        if (responseHeaders.keySet().stream().noneMatch(HttpResponse.CONTENT_TYPE::equalsIgnoreCase)) {
            responseHeaders.put(HttpResponse.CONTENT_TYPE, contentType);
        }
        return new HttpResponse(status, responseHeaders, bytes, false);
    }

    private static boolean isToken(String name) {
        if (name.isEmpty()) {
            return false;
        }
        for (var i = 0; i < name.length(); i++) {
            final var c = name.charAt(i);
            if (c >= TOKEN_CHARS.length || !TOKEN_CHARS[c]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isFieldValue(String text) {
        for (var i = 0; i < text.length(); i++) {
            final var c = text.charAt(i);
            if (c > 0xFF || c == 0x7F || c < ' ' && c != '\t') {
                return false;
            }
        }
        return true;
    }
}
