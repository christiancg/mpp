package org.ccg.mpp.unit.ops.entrypoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.ccg.mpp.ejson.EJson;
import org.ccg.mpp.ejson.elements.JsonBaseElement;
import org.ccg.mpp.ejson.elements.JsonNull;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.http.HttpResponse;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.entrypoint.EntrypointReply;
import org.ccg.mpp.simplejs.host.ScriptResult;
import org.junit.jupiter.api.Test;

public class EntrypointReplyTest {
    private static final String RUN_ID = "run-1";
    private final EJson eJson = IocContainer.get(EJson.class);

    private HttpResponse reply(String resultJson) {
        return EntrypointReply.of(ScriptResult.value(eJson.fromJson(resultJson, JsonBaseElement.class)), RUN_ID);
    }

    private static String body(HttpResponse response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private static String contentType(HttpResponse response) {
        return response.headers().get(HttpResponse.CONTENT_TYPE);
    }

    @Test
    public void test_plain_object_is_200_json() {
        final var response = reply("{\"_id\":\"o1\",\"qty\":2}");

        assertEquals(200, response.status());
        assertEquals("{\"_id\":\"o1\",\"qty\":2}", body(response));
        assertEquals(HttpResponse.JSON, contentType(response));
    }

    @Test
    public void test_string_result_is_text_plain() {
        final var response = EntrypointReply.of(ScriptResult.value(new JsonString("héllo")), RUN_ID);

        assertEquals("héllo", body(response));
        assertEquals(HttpResponse.PLAIN_TEXT, contentType(response));
    }

    @Test
    public void test_null_result_is_empty_200() {
        final var response = EntrypointReply.of(ScriptResult.value(JsonNull.INSTANCE), RUN_ID);

        assertEquals(200, response.status());
        assertEquals(0, response.body().length);
        assertFalse(response.headers().containsKey(HttpResponse.CONTENT_TYPE));
    }

    @Test
    public void test_envelope_status_headers_body() {
        final var response = reply("{\"status\":201,\"headers\":{\"Location\":\"/shop/orders/o1\",\"X-Count\":3,"
                + "\"X-Flag\":true},\"body\":{\"_id\":\"o1\"}}");

        assertEquals(201, response.status());
        assertEquals("/shop/orders/o1", response.headers().get("Location"));
        assertEquals("3", response.headers().get("X-Count"));
        assertEquals("true", response.headers().get("X-Flag"));
        assertEquals("{\"_id\":\"o1\"}", body(response));
    }

    @Test
    public void test_envelope_without_body_is_empty() {
        final var response = reply("{\"status\":202,\"headers\":null}");

        assertEquals(202, response.status());
        assertEquals(0, response.body().length);
    }

    @Test
    public void test_envelope_content_type_overrides_default() {
        final var response = reply("{\"status\":200,\"headers\":{\"content-type\":\"text/csv\"},\"body\":\"a,b\"}");

        assertEquals("text/csv", response.headers().get("content-type"));
        assertFalse(response.headers().containsKey(HttpResponse.CONTENT_TYPE));
    }

    @Test
    public void test_non_integer_or_out_of_range_status_is_not_an_envelope() {
        for (final var result : new String[]{"{\"status\":\"active\"}", "{\"status\":99}", "{\"status\":600}",
                "{\"status\":200.5}"}) {
            final var response = reply(result);

            assertEquals(200, response.status(), result);
            assertEquals(result, body(response));
        }
    }

    @Test
    public void test_invalid_envelope_headers_are_500() {
        for (final var headers : new String[]{"{\"X-Bad\":\"a\\r\\nSet-Cookie: x\"}", "{\"Bad Name\":\"x\"}",
                "{\"X-Obj\":{}}", "{\"X-Null\":null}", "\"not-an-object\"", "{\"X-Wide\":\"€\"}", "{\"\":\"x\"}"}) {
            final var response = reply("{\"status\":200,\"headers\":" + headers + "}");

            assertEquals(500, response.status(), headers);
            assertTrue(body(response).contains(RUN_ID), headers);
        }
    }

    @Test
    public void test_server_owned_headers_are_dropped() {
        final var response = reply("{\"status\":200,\"headers\":{\"Content-Length\":\"9\",\"Connection\":\"close\","
                + "\"Transfer-Encoding\":\"chunked\",\"Keep-Alive\":\"x\",\"X-Kept\":\"\\tok\"},\"body\":\"x\"}");

        assertEquals("\tok", response.headers().get("X-Kept"));
        assertEquals(2, response.headers().size());
    }

    @Test
    public void test_204_and_304_send_no_body() {
        assertTrue(reply("{\"status\":204,\"body\":\"ignored\"}").hasNoBody());
        assertTrue(reply("{\"status\":304}").hasNoBody());
    }

    @Test
    public void test_script_error_is_500_with_run_id_and_no_message() {
        final var response = EntrypointReply.of(ScriptResult.error("Error", "secret detail"), RUN_ID);

        assertEquals(500, response.status());
        assertEquals("{\"error\":\"Internal Server Error\",\"runId\":\"run-1\"}", body(response));
        assertFalse(body(response).contains("secret"));
    }

    @Test
    public void test_timeout_is_500() {
        assertEquals(500, EntrypointReply.of(ScriptResult.error("TimeoutError", "timed out"), RUN_ID).status());
    }
}
