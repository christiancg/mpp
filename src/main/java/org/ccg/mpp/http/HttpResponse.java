package org.ccg.mpp.http;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record HttpResponse(int status, Map<String, String> headers, byte[] body, boolean closes) {
    public static final String JSON = "application/json";
    public static final String PLAIN_TEXT = "text/plain; charset=utf-8";
    public static final String CONTENT_TYPE = "Content-Type";
    private static final byte[] NO_BODY = new byte[0];

    public HttpResponse {
        headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        body = body == null ? NO_BODY : body;
    }

    public static HttpResponse error(int status, String reason) {
        final var json = "{\"error\":\"" + escaped(reason) + "\"}";
        return new HttpResponse(status, Map.of(CONTENT_TYPE, JSON), json.getBytes(StandardCharsets.UTF_8), false);
    }

    public HttpResponse withHeader(String name, String value) {
        final var copy = new LinkedHashMap<>(headers);
        copy.put(name, value);
        return new HttpResponse(status, copy, body, closes);
    }

    public HttpResponse closing() {
        return closes ? this : new HttpResponse(status, headers, body, true);
    }

    public boolean hasNoBody() {
        return status < 200 || status == 204 || status == 304;
    }

    private static String escaped(String reason) {
        final var out = new StringBuilder(reason.length());
        for (var i = 0; i < reason.length(); i++) {
            final var c = reason.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c >= ' ') {
                out.append(c);
            }
        }
        return out.toString();
    }
}
