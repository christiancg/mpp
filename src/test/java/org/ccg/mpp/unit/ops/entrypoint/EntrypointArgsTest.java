package org.ccg.mpp.unit.ops.entrypoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.http.HttpRequest;
import org.ccg.mpp.http.HttpRequestHead;
import org.ccg.mpp.ops.entrypoint.EntrypointArgs;
import org.ccg.mpp.ops.entrypoint.MalformedBodyException;
import org.junit.jupiter.api.Test;

public class EntrypointArgsTest {

    private static HttpRequest request(String requestLine, String headers) throws IOException {
        final var text = requestLine + " HTTP/1.1\r\n" + headers + "\r\n";
        final var head = HttpRequestHead.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.ISO_8859_1)),
                4096);
        return new HttpRequest(head, "203.0.113.7", 0, () -> new byte[0]);
    }

    private static JsonObject args(String requestLine, String headers, String body) throws IOException {
        return EntrypointArgs.of(request(requestLine, headers), "getOrder", "/orders/o1", Map.of("id", "o1"),
                body.getBytes(StandardCharsets.UTF_8));
    }

    private static String text(JsonObject args, String field) {
        return args.get(field).asJsonString().getValue();
    }

    @Test
    public void test_carries_the_request_identity() throws IOException {
        final var args = args("GET /shop/orders/o1", "", "");

        assertEquals("getOrder", text(args, "entrypoint"));
        assertEquals("GET", text(args, "method"));
        assertEquals("/orders/o1", text(args, "path"));
        assertEquals("o1", text(args.get("pathParams").asJsonObject(), "id"));
        assertEquals("203.0.113.7", text(args, "remoteAddress"));
    }

    @Test
    public void test_query_repeated_keys_become_arrays() throws IOException {
        final var query = args("GET /shop/x?tag=a&expand=lines&tag=b", "", "").get("query").asJsonObject();

        assertEquals("lines", text(query, "expand"));
        assertEquals(2, query.get("tag").asJsonArray().size());
        assertEquals("b", query.get("tag").asJsonArray().get(1).asJsonString().getValue());
    }

    @Test
    public void test_query_plus_and_percent_decoding_and_key_without_value() throws IOException {
        final var query = args("GET /shop/x?q=a+b%21&flag", "", "").get("query").asJsonObject();

        assertEquals("a b!", text(query, "q"));
        assertEquals("", text(query, "flag"));
    }

    @Test
    public void test_a_malformed_query_is_refused() {
        assertThrows(MalformedBodyException.class, () -> args("GET /shop/x?q=%zz", "", ""));
    }

    @Test
    public void test_headers_lowercased_and_repeated_joined() throws IOException {
        final var headers = args("GET /shop/x", "X-Tag: a\r\nx-tag: b\r\n", "").get("headers").asJsonObject();

        assertEquals("a, b", text(headers, "x-tag"));
    }

    @Test
    public void test_json_body_parsed() throws IOException {
        final var body = args("POST /shop/x", "Content-Type: application/json; charset=utf-8\r\n", "{\"qty\":2}")
                .get("body").asJsonObject();

        assertEquals(2.0, body.get("qty").asJsonNumber().doubleValue());
    }

    @Test
    public void test_plus_json_content_type_parsed_and_a_top_level_scalar_refused() throws IOException {
        final var body = args("POST /shop/x", "Content-Type: application/merge-patch+json\r\n", "[1,2]").get("body");

        assertTrue(body.isJsonArray());
        assertThrows(MalformedBodyException.class,
                () -> args("POST /shop/x", "Content-Type: application/json\r\n", "42"));
    }

    @Test
    public void test_non_json_body_is_string() throws IOException {
        assertEquals("a=b", text(args("POST /shop/x", "Content-Type: text/plain\r\n", "a=b"), "body"));
        assertEquals("{\"x\":1}", text(args("POST /shop/x", "", "{\"x\":1}"), "body"));
    }

    @Test
    public void test_empty_body_is_null() throws IOException {
        assertTrue(args("POST /shop/x", "Content-Type: application/json\r\n", "").get("body").isJsonNull());
    }

    @Test
    public void test_malformed_json_body_throws() {
        assertThrows(MalformedBodyException.class,
                () -> args("POST /shop/x", "Content-Type: application/json\r\n", "{bad"));
    }

    @Test
    public void test_body_over_parse_depth_throws() {
        final var deep = "[".repeat(200) + "]".repeat(200);

        assertThrows(MalformedBodyException.class,
                () -> args("POST /shop/x", "Content-Type: application/json\r\n", deep));
    }

    @Test
    public void test_a_text_body_that_is_not_utf8_throws() throws IOException {
        final var request = request("POST /shop/x", "");

        assertThrows(MalformedBodyException.class,
                () -> EntrypointArgs.of(request, "e", "/x", Map.of(), new byte[]{(byte) 0xC3, (byte) 0x28}));
    }
}
