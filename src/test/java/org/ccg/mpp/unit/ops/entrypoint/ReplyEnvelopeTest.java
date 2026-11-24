package org.ccg.mpp.unit.ops.entrypoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.ccg.mpp.ejson.EJson;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.http.HttpResponse;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.entrypoint.InvalidEnvelopeException;
import org.ccg.mpp.ops.entrypoint.ReplyEnvelope;
import org.junit.jupiter.api.Test;

public class ReplyEnvelopeTest {
    private final EJson eJson = IocContainer.get(EJson.class);

    private JsonObject json(String text) {
        return eJson.fromJson(text, JsonObject.class);
    }

    private static String body(HttpResponse response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private void assertRefused(ReplyEnvelope envelope, String changes) {
        assertThrows(InvalidEnvelopeException.class, () -> assertNotNull(envelope.withChanges(json(changes))));
    }

    private ReplyEnvelope target() throws InvalidEnvelopeException {
        return ReplyEnvelope.of(200, json("{\"Content-Type\":\"application/json\",\"X-Trace\":\"t1\"}"),
                json("{\"_id\":\"o1\"}"));
    }

    @Test
    public void test_with_changes_merges_headers_case_insensitively() throws Exception {
        final var changed = target().withChanges(json("{\"headers\":{\"x-trace\":\"t2\",\"X-New\":\"n\"}}"));

        assertEquals(Map.of("Content-Type", "application/json", "x-trace", "t2", "X-New", "n"), changed.headers());
    }

    @Test
    public void test_a_null_header_removes_it() throws Exception {
        final var changed = target().withChanges(json("{\"headers\":{\"X-TRACE\":null}}"));

        assertEquals(Map.of("Content-Type", "application/json"), changed.headers());
    }

    @Test
    public void test_body_and_status_are_replaced() throws Exception {
        final var changed = target().withChanges(json("{\"status\":202,\"body\":\"done\"}"));

        assertEquals(202, changed.status());
        assertEquals("done", changed.body().asJsonString().getValue());
        assertEquals(target().headers(), changed.headers());
    }

    @Test
    public void test_no_changes_keep_the_envelope() throws Exception {
        final var original = target();

        assertEquals(original, original.withChanges(new JsonObject()));
        assertEquals(original, original.withChanges(json("{\"headers\":null}")));
    }

    @Test
    public void test_server_owned_headers_are_dropped() throws Exception {
        final var changed = target()
                .withChanges(json("{\"headers\":{\"Content-Length\":\"1\",\"connection\":\"close\"}}"));

        assertFalse(changed.headers().containsKey("Content-Length"));
        assertFalse(changed.headers().containsKey("connection"));
    }

    @Test
    public void test_a_non_token_name_or_control_value_is_refused() throws Exception {
        final var original = target();

        assertRefused(original, "{\"headers\":{\"a b\":\"x\"}}");
        assertRefused(original, "{\"headers\":{\"X-A\":\"a\\r\\nb\"}}");
        assertRefused(original, "{\"headers\":\"x\"}");
        assertThrows(InvalidEnvelopeException.class,
                () -> assertNotNull(ReplyEnvelope.of(200, json("{\"X-A\":null}"), null)));
    }

    @Test
    public void test_to_args_shapes_status_headers_and_body() throws Exception {
        final var args = target().toArgs();

        assertEquals(200.0, args.get("status").asJsonNumber().doubleValue());
        assertEquals("t1", args.get("headers").asJsonObject().get("x-trace").asJsonString().getValue());
        assertEquals("o1", args.get("body").asJsonObject().get("_id").asJsonString().getValue());
    }

    @Test
    public void test_a_failure_carries_its_run_id() {
        final var response = ReplyEnvelope.failure("run-9").toHttpResponse();

        assertEquals(500, response.status());
        assertEquals("{\"error\":\"Internal Server Error\",\"runId\":\"run-9\"}", body(response));
        assertEquals(HttpResponse.JSON, response.headers().get(HttpResponse.CONTENT_TYPE));
    }

    @Test
    public void test_a_failure_without_a_run_id_omits_it() {
        assertEquals("{\"error\":\"Internal Server Error\"}", body(ReplyEnvelope.failure(null).toHttpResponse()));
    }

    @Test
    public void test_a_changed_body_picks_its_content_type() throws Exception {
        final var asText = ReplyEnvelope.of(200, null, json("{\"a\":1}")).withChanges(json("{\"body\":\"plain\"}"))
                .toHttpResponse();

        assertEquals(HttpResponse.PLAIN_TEXT, asText.headers().get(HttpResponse.CONTENT_TYPE));
        assertEquals("plain", body(asText));
        assertEquals(0, ReplyEnvelope.of(200, null, new JsonString("x")).withChanges(json("{\"body\":null}"))
                .toHttpResponse().body().length);
    }
}
