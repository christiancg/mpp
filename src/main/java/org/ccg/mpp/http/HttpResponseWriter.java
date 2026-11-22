package org.ccg.mpp.http;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class HttpResponseWriter {
    private static final int HEAD_CAPACITY = 256;
    private static final Map<Integer, String> REASONS = Map.ofEntries(Map.entry(100, "Continue"),
            Map.entry(101, "Switching Protocols"), Map.entry(200, "OK"), Map.entry(201, "Created"),
            Map.entry(202, "Accepted"), Map.entry(204, "No Content"), Map.entry(206, "Partial Content"),
            Map.entry(301, "Moved Permanently"), Map.entry(302, "Found"), Map.entry(303, "See Other"),
            Map.entry(304, "Not Modified"), Map.entry(307, "Temporary Redirect"), Map.entry(308, "Permanent Redirect"),
            Map.entry(400, "Bad Request"), Map.entry(401, "Unauthorized"), Map.entry(403, "Forbidden"),
            Map.entry(404, "Not Found"), Map.entry(405, "Method Not Allowed"), Map.entry(406, "Not Acceptable"),
            Map.entry(408, "Request Timeout"), Map.entry(409, "Conflict"), Map.entry(410, "Gone"),
            Map.entry(411, "Length Required"), Map.entry(412, "Precondition Failed"),
            Map.entry(413, "Content Too Large"), Map.entry(415, "Unsupported Media Type"),
            Map.entry(422, "Unprocessable Content"), Map.entry(429, "Too Many Requests"),
            Map.entry(431, "Request Header Fields Too Large"), Map.entry(500, "Internal Server Error"),
            Map.entry(501, "Not Implemented"), Map.entry(502, "Bad Gateway"), Map.entry(503, "Service Unavailable"),
            Map.entry(504, "Gateway Timeout"));

    private HttpResponseWriter() {
    }

    public static void write(OutputStream out, HttpResponse response, boolean keepAlive) throws IOException {
        final var body = response.hasNoBody() ? new byte[0] : response.body();
        final var head = new StringBuilder(HEAD_CAPACITY).append("HTTP/1.1 ").append(response.status()).append(' ')
                .append(reasonOf(response.status())).append("\r\n");
        for (final var header : response.headers().entrySet()) {
            head.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
        }
        if (!response.hasNoBody()) {
            head.append("Content-Length: ").append(body.length).append("\r\n");
        }
        head.append("Connection: ").append(keepAlive ? "keep-alive" : "close").append("\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    public static String reasonOf(int status) {
        final var known = REASONS.get(status);
        if (known != null) {
            return known;
        }
        return switch (status / 100) {
            case 1 -> "Informational";
            case 2 -> "Success";
            case 3 -> "Redirection";
            case 4 -> "Client Error";
            default -> "Server Error";
        };
    }
}
