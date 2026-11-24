package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.MiddlewarePhase;
import org.ccg.mpp.http.HttpResponse;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.ScriptAdmission;
import org.ccg.mpp.ops.middleware.MiddlewareChains;
import org.ccg.mpp.test.TestGlobals;
import org.junit.jupiter.api.Test;

public class MiddlewareEgressTest extends MiddlewareHttpFixture {
    private static final String RESPONSE_ECHO = "reject(200, {status: args.response.status,"
            + " body: args.response.body, headers: args.response.headers, locals: args.locals,"
            + " requestHeaders: args.headers, pathParams: args.pathParams});";

    @Test
    public void test_egress_changes_the_target_response() throws Exception {
        expose("read", "GET", "/r", "return {status: 200, headers: {\"X-From\": \"target\"}, body: {a: 1}};");
        middleware("cors", MiddlewarePhase.EGRESS, 0,
                "next({status: 202, headers: {\"access-control-allow-origin\": \"*\", \"x-from\": null}});");
        final var response = call("GET " + BASE + "/r");

        assertEquals(202, response.status());
        assertEquals("*", response.headers().get("access-control-allow-origin"));
        assertFalse(response.headers().containsKey("X-From"));
        assertEquals("{\"a\":1}", body(response));
    }

    @Test
    public void test_egress_changes_accumulate_and_a_string_body_is_text() throws Exception {
        expose("read", "GET", "/r", "return {a: 1};");
        middleware("first", MiddlewarePhase.EGRESS, 1, "next({status: 202, headers: {\"X-One\": \"1\"}});");
        middleware("second", MiddlewarePhase.EGRESS, 2, "next({body: \"status was \" + args.response.status});");
        final var response = call("GET " + BASE + "/r");

        assertEquals(202, response.status());
        assertEquals("1", response.headers().get("X-One"));
        assertEquals("status was 202", body(response));
        assertEquals(HttpResponse.PLAIN_TEXT, response.headers().get(HttpResponse.CONTENT_TYPE));
    }

    @Test
    public void test_egress_sees_the_request_as_the_target_saw_it() throws Exception {
        expose("read", "GET", "/orders/{id}", "return {ok: true};");
        middleware("rewrite", MiddlewarePhase.INGRESS, 0,
                "next({pathParams: {id: \"O1\"}, headers: {\"x-in\": \"set\"}, locals: {user: \"u1\"}});");
        middleware("echo", MiddlewarePhase.EGRESS, 0, RESPONSE_ECHO);
        final var echoed = json(call("GET " + BASE + "/orders/o1"));

        assertEquals("O1", text(echoed.get("pathParams").asJsonObject(), "id"));
        assertEquals("set", text(echoed.get("requestHeaders").asJsonObject(), "x-in"));
        assertEquals("u1", text(echoed.get("locals").asJsonObject(), "user"));
        assertTrue(echoed.get("body").asJsonObject().get("ok").asJsonBoolean().getValue());
        assertTrue(echoed.get("headers").asJsonObject().isEmpty());
    }

    @Test
    public void test_egress_runs_after_an_ingress_reject() throws Exception {
        expose("read", "GET", "/r", "return 1;");
        middleware("auth", MiddlewarePhase.INGRESS, 0, "reject(401, {error: \"no\"});");
        middleware("echo", MiddlewarePhase.EGRESS, 0, RESPONSE_ECHO);
        final var echoed = json(call("GET " + BASE + "/r"));

        assertEquals(401.0, echoed.get("status").asJsonNumber().doubleValue());
        assertEquals("no", text(echoed.get("body").asJsonObject(), "error"));
    }

    @Test
    public void test_egress_runs_on_a_target_failure_and_sees_500() throws Exception {
        expose("boom", "GET", "/r", "throw new Error('secret');");
        middleware("echo", MiddlewarePhase.EGRESS, 0, RESPONSE_ECHO);
        final var echoed = json(call("GET " + BASE + "/r"));

        assertEquals(500.0, echoed.get("status").asJsonNumber().doubleValue());
        assertTrue(echoed.get("body").asJsonObject().has("runId"));
        assertFalse(echoed.toString().contains("secret"));
    }

    @Test
    public void test_an_egress_reject_stops_later_egress() throws Exception {
        expose("read", "GET", "/r", "return 1;");
        middleware("replace", MiddlewarePhase.EGRESS, 1, "reject(203, \"replaced\");");
        middleware("later", MiddlewarePhase.EGRESS, 2, "throw new Error('later must not run');");
        final var response = call("GET " + BASE + "/r");

        assertEquals(203, response.status());
        assertEquals("replaced", body(response));
    }

    @Test
    public void test_egress_locals_reach_later_egress() throws Exception {
        expose("read", "GET", "/r", "return 1;");
        middleware("first", MiddlewarePhase.EGRESS, 1, "next({locals: {seen: \"first\"}});");
        middleware("second", MiddlewarePhase.EGRESS, 2, "reject(200, args.locals.seen);");

        assertEquals("first", body(call("GET " + BASE + "/r")));
    }

    @Test
    public void test_an_undecided_egress_is_500() throws Exception {
        expose("read", "GET", "/r", "return 1;");
        middleware("silent", MiddlewarePhase.EGRESS, 0, "const x = 1;");

        assertEquals(500, call("GET " + BASE + "/r").status());
    }

    @Test
    public void test_a_failing_egress_is_500_with_its_run_id() throws Exception {
        expose("read", "GET", "/r", "return 1;");
        middleware("boom", MiddlewarePhase.EGRESS, 0, "throw new Error('secret');");
        final var response = call("GET " + BASE + "/r");

        assertEquals(500, response.status());
        assertTrue(body(response).contains("runId"));
        assertFalse(body(response).contains("secret"));
    }

    @Test
    public void test_an_invalid_egress_header_is_500() throws Exception {
        expose("read", "GET", "/r", "return 1;");
        middleware("bad", MiddlewarePhase.EGRESS, 0, "next({headers: {\"x-bad\": \"a\\r\\nb\"}});");

        assertEquals(500, call("GET " + BASE + "/r").status());
    }

    @Test
    public void test_unreadable_middlewares_answer_500_before_admission() throws Exception {
        expose("read", "GET", "/r", "return 1;");
        fs.metadataFiles().writeMiddleware(TestGlobals.PROJECT, "broken", "{not json");
        cache.removeMiddleware(TestGlobals.PROJECT, "broken");
        IocContainer.get(MiddlewareChains.class).reload(TestGlobals.PROJECT);
        final var admission = IocContainer.get(ScriptAdmission.class);
        admission.reconfigure(0, 0L, 1, 0);
        try {
            final var held = assertInstanceOf(ScriptAdmission.Permit.class,
                    admission.acquire(ADMIN, TestGlobals.PROJECT, false));
            try (held) {
                assertEquals(500, call("GET " + BASE + "/r").status());
            }
        } finally {
            admission.reconfigure(configuration.getMaxConcurrentScripts(), configuration.getScriptQueueWaitMs(),
                    configuration.getMaxConcurrentScriptsPerUser(), configuration.getMaxConcurrentScriptsPerProject());
            fs.metadataFiles().deleteMiddleware(TestGlobals.PROJECT, "broken");
        }
    }

    @Test
    public void test_a_chain_takes_one_admission_permit() throws Exception {
        expose("read", "GET", "/r", "return 1;");
        middleware("in", MiddlewarePhase.INGRESS, 0, "next();");
        middleware("out", MiddlewarePhase.EGRESS, 0, "next();");
        final var admission = IocContainer.get(ScriptAdmission.class);
        admission.reconfigure(0, 0L, 1, 0);
        try {
            assertEquals(200, call("GET " + BASE + "/r").status());
        } finally {
            admission.reconfigure(configuration.getMaxConcurrentScripts(), configuration.getScriptQueueWaitMs(),
                    configuration.getMaxConcurrentScriptsPerUser(), configuration.getMaxConcurrentScriptsPerProject());
        }
    }

    @Test
    public void test_server_replies_never_reach_a_middleware() throws Exception {
        expose("read", "GET", "/r", "return 1;");
        middleware("rewrite", MiddlewarePhase.EGRESS, 0, "reject(299, \"from middleware\");");
        final var admission = IocContainer.get(ScriptAdmission.class);

        assertEquals(404, call("GET " + BASE + "/missing").status());
        assertEquals(405, call("POST " + BASE + "/r").status());
        admission.reconfigure(0, 0L, 1, 0);
        try {
            final var held = assertInstanceOf(ScriptAdmission.Permit.class,
                    admission.acquire(ADMIN, TestGlobals.PROJECT, false));
            try (held) {
                final var refused = call("GET " + BASE + "/r");
                assertEquals(503, refused.status());
                assertEquals(String.valueOf(Globals.HTTP_RETRY_AFTER_SECONDS), refused.headers().get("Retry-After"));
            }
        } finally {
            admission.reconfigure(configuration.getMaxConcurrentScripts(), configuration.getScriptQueueWaitMs(),
                    configuration.getMaxConcurrentScriptsPerUser(), configuration.getMaxConcurrentScriptsPerProject());
        }
        assertEquals(299, call("GET " + BASE + "/r").status());
    }
}
