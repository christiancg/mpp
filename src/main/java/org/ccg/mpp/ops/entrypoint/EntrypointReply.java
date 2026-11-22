package org.ccg.mpp.ops.entrypoint;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.ccg.mpp.ejson.EJson;
import org.ccg.mpp.ejson.elements.JsonBaseElement;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.http.HttpResponse;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.log.Logger;
import org.ccg.mpp.simplejs.host.ScriptResult;

public final class EntrypointReply {
    private static final EJson eJson = IocContainer.get(EJson.class);
    private static final Logger logger = Logger.logFor(EntrypointReply.class);
    private static final String STATUS = "status";
    private static final String HEADERS = "headers";
    private static final String BODY = "body";
    private static final int LOWEST_STATUS = 100;
    private static final int HIGHEST_STATUS = 599;
    private static final int INTERNAL_ERROR = 500;
    private static final Set<String> SERVER_OWNED_HEADERS = Set.of("content-length", "transfer-encoding", "connection",
            "keep-alive");
    private static final boolean[] TOKEN_CHARS = tokenChars();

    private EntrypointReply() {
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

    public static HttpResponse of(ScriptResult result, String runId) {
        if (result.isError()) {
            return failure(runId);
        }
        final var value = result.getValue();
        final var envelopeStatus = envelopeStatusOf(value);
        if (envelopeStatus < 0) {
            return withBody(200, new LinkedHashMap<>(), value);
        }
        try {
            return envelope(value.asJsonObject(), envelopeStatus);
        } catch (InvalidEnvelopeException e) {
            logger.warning(
                    "Entrypoint run " + runId + " returned an envelope the server cannot send: " + e.getMessage());
            return failure(runId);
        }
    }

    public static HttpResponse failure(String runId) {
        final var json = new JsonObject();
        json.addProperty("error", "Internal Server Error");
        json.addProperty("runId", runId);
        return new HttpResponse(INTERNAL_ERROR, Map.of(HttpResponse.CONTENT_TYPE, HttpResponse.JSON),
                eJson.toJson(json).getBytes(StandardCharsets.UTF_8), false);
    }

    static int envelopeStatusOf(JsonBaseElement value) {
        if (value == null || !value.isJsonObject()) {
            return -1;
        }
        final var status = value.asJsonObject().get(STATUS);
        if (status == null || !status.isJsonNumber()) {
            return -1;
        }
        final var number = status.asJsonNumber().doubleValue();
        if (number != Math.rint(number) || number < LOWEST_STATUS || number > HIGHEST_STATUS) {
            return -1;
        }
        return (int) number;
    }

    private static HttpResponse envelope(JsonObject envelope, int status) throws InvalidEnvelopeException {
        final var headers = new LinkedHashMap<String, String>();
        final var declared = envelope.get(HEADERS);
        if (declared != null && !declared.isJsonNull()) {
            if (!declared.isJsonObject()) {
                throw new InvalidEnvelopeException("'headers' must be an object");
            }
            for (final var header : declared.asJsonObject().entrySet()) {
                addHeader(headers, header.getKey(), header.getValue());
            }
        }
        return withBody(status, headers, envelope.get(BODY));
    }

    private static void addHeader(Map<String, String> headers, String name, JsonBaseElement value)
            throws InvalidEnvelopeException {
        if (!isToken(name)) {
            throw new InvalidEnvelopeException("the header name '" + name + "' is not an HTTP token");
        }
        if (SERVER_OWNED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
            return;
        }
        final String text;
        if (value != null && value.isJsonString()) {
            text = value.asJsonString().getValue();
        } else if (value != null && (value.isJsonNumber() || value.isJsonBoolean())) {
            text = eJson.toJson(value);
        } else {
            throw new InvalidEnvelopeException("the header '" + name + "' must be a string, number or boolean");
        }
        if (!isFieldValue(text)) {
            throw new InvalidEnvelopeException("the header '" + name + "' holds a control or non-Latin-1 character");
        }
        headers.put(name, text);
    }

    private static HttpResponse withBody(int status, Map<String, String> headers, JsonBaseElement value) {
        if (value == null || value.isJsonNull()) {
            return new HttpResponse(status, headers, null, false);
        }
        final byte[] body;
        final String contentType;
        if (value.isJsonString()) {
            body = value.asJsonString().getValue().getBytes(StandardCharsets.UTF_8);
            contentType = HttpResponse.PLAIN_TEXT;
        } else {
            body = eJson.toJson(value).getBytes(StandardCharsets.UTF_8);
            contentType = HttpResponse.JSON;
        }
        if (headers.keySet().stream().noneMatch(HttpResponse.CONTENT_TYPE::equalsIgnoreCase)) {
            headers.put(HttpResponse.CONTENT_TYPE, contentType);
        }
        return new HttpResponse(status, headers, body, false);
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

    private static final class InvalidEnvelopeException extends Exception {
        InvalidEnvelopeException(String detail) {
            super(detail);
        }
    }
}
