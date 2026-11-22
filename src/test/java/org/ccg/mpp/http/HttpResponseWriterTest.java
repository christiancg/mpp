package org.ccg.mpp.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class HttpResponseWriterTest {

    private static String written(HttpResponse response, boolean keepAlive) throws IOException {
        final var out = new ByteArrayOutputStream();
        HttpResponseWriter.write(out, response, keepAlive);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    public void test_writes_status_headers_length_and_body() throws IOException {
        final var response = new HttpResponse(201, Map.of("Location", "/shop/orders/o1"),
                "{}".getBytes(StandardCharsets.UTF_8), false);

        assertEquals(String.join("\r\n", "HTTP/1.1 201 Created", "Location: /shop/orders/o1", "Content-Length: 2",
                "Connection: keep-alive", "", "{}"), written(response, true));
    }

    @Test
    public void test_no_content_and_not_modified_carry_no_body_or_length() throws IOException {
        for (final var status : new int[]{204, 304, 101}) {
            final var text = written(
                    new HttpResponse(status, Map.of(), "ignored".getBytes(StandardCharsets.UTF_8), false), false);

            assertFalse(text.contains("Content-Length"), text);
            assertTrue(text.endsWith("Connection: close\r\n\r\n"), text);
        }
    }

    @Test
    public void test_unlisted_codes_use_their_class_phrase() {
        assertEquals("Not Found", HttpResponseWriter.reasonOf(404));
        assertEquals("Informational", HttpResponseWriter.reasonOf(199));
        assertEquals("Success", HttpResponseWriter.reasonOf(299));
        assertEquals("Redirection", HttpResponseWriter.reasonOf(399));
        assertEquals("Client Error", HttpResponseWriter.reasonOf(499));
        assertEquals("Server Error", HttpResponseWriter.reasonOf(599));
    }

    @Test
    public void test_an_error_reply_is_escaped_json() {
        final var response = HttpResponse.error(400, "bad \"quote\" \\ and\ncontrol");

        assertEquals("{\"error\":\"bad \\\"quote\\\" \\\\ andcontrol\"}",
                new String(response.body(), StandardCharsets.UTF_8));
        assertEquals(HttpResponse.JSON, response.headers().get(HttpResponse.CONTENT_TYPE));
        assertFalse(response.closes());
    }

    @Test
    public void test_closing_and_with_header_return_copies() {
        final var response = HttpResponse.error(503, "busy");
        final var closing = response.closing();

        assertTrue(closing.closes());
        assertSame(closing, closing.closing());
        assertEquals("1", response.withHeader("Retry-After", "1").headers().get("Retry-After"));
        assertFalse(response.headers().containsKey("Retry-After"));
    }
}
