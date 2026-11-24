package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.ccg.mpp.data.MiddlewarePhase;
import org.ccg.mpp.ops.MiddlewareOperationHelper;
import org.ccg.mpp.ops.req.InlineProcedure;
import org.ccg.mpp.ops.req.MiddlewareCriteriaSpec;
import org.ccg.mpp.test.MiddlewareSpecs;
import org.ccg.mpp.test.TestGlobals;
import org.junit.jupiter.api.Test;

public class MiddlewareIngressTest extends MiddlewareHttpFixture {
    @Test
    public void test_without_middlewares_only_the_target_runs_and_sees_empty_locals() throws Exception {
        expose("echo", "GET", "/orders/{id}", ECHO);
        final var args = json(call("GET " + BASE + "/orders/o1"));

        assertTrue(args.get("locals").asJsonObject().isEmpty());
        assertFalse(args.has("middleware"));
        assertFalse(args.has("response"));
    }

    @Test
    public void test_next_passes_locals_headers_query_and_body_to_the_target() throws Exception {
        expose("echo", "POST", "/orders/{id}", ECHO);
        middleware("rewrite", MiddlewarePhase.INGRESS, 0,
                "next({locals: {user: \"u1\"}, headers: {\"X-Added\": \"yes\","
                        + " authorization: null}, query: {page: \"2\", drop: null}, body: {replaced: true}});");
        final var args = json(call("POST " + BASE + "/orders/o1?drop=1&keep=k",
                "Authorization: Bearer t\r\nContent-Type: application/json\r\n", "{\"original\":true}"));

        assertEquals("u1", text(args.get("locals").asJsonObject(), "user"));
        final var headers = args.get("headers").asJsonObject();
        assertEquals("yes", text(headers, "x-added"));
        assertFalse(headers.has("authorization"));
        final var query = args.get("query").asJsonObject();
        assertEquals("2", text(query, "page"));
        assertEquals("k", text(query, "keep"));
        assertFalse(query.has("drop"));
        assertTrue(args.get("body").asJsonObject().has("replaced"));
        assertFalse(args.has("middleware"));
    }

    @Test
    public void test_ingress_rewrites_the_path_params_the_target_reads() throws Exception {
        expose("echo", "GET", "/orders/{id}", ECHO);
        middleware("upper", MiddlewarePhase.INGRESS, 0, "next({pathParams: {id: args.pathParams.id.toUpperCase()}});");

        assertEquals("O1", text(json(call("GET " + BASE + "/orders/o1")).get("pathParams").asJsonObject(), "id"));
    }

    @Test
    public void test_changes_accumulate_across_middlewares_in_order() throws Exception {
        expose("echo", "GET", "/r", ECHO);
        middleware("second", MiddlewarePhase.INGRESS, 2, "next({headers: {\"x-v\": args.headers[\"x-v\"] + \"-2\"},"
                + " locals: {trace: args.locals.trace + \",second\"}});");
        middleware("first", MiddlewarePhase.INGRESS, 1,
                "next({headers: {\"x-v\": \"1\"}, locals: {trace: \"first\"}});");
        middleware("tie-b", MiddlewarePhase.INGRESS, 3, "next({locals: {trace: args.locals.trace + \",tie-b\"}});");
        middleware("tie-a", MiddlewarePhase.INGRESS, 3, "next({locals: {trace: args.locals.trace + \",tie-a\"}});");
        final var args = json(call("GET " + BASE + "/r"));

        assertEquals("1-2", text(args.get("headers").asJsonObject(), "x-v"));
        assertEquals("first,second,tie-a,tie-b", text(args.get("locals").asJsonObject(), "trace"));
    }

    @Test
    public void test_a_middleware_sees_its_own_description() throws Exception {
        expose("echo", "GET", "/r", ECHO);
        middleware("about", MiddlewarePhase.INGRESS, 7,
                "next({locals: {me: args.middleware.name + \"/\" + args.middleware.phase + \"/\" + args.middleware.order}});");

        assertEquals("about/INGRESS/7", text(json(call("GET " + BASE + "/r")).get("locals").asJsonObject(), "me"));
    }

    @Test
    public void test_reject_skips_the_target_and_later_ingress() throws Exception {
        expose("boom", "GET", "/r", "throw new Error('target must not run');");
        middleware("auth", MiddlewarePhase.INGRESS, 1,
                "reject(401, {error: \"unauthorized\"}, {\"WWW-Authenticate\": \"Bearer\"});");
        middleware("later", MiddlewarePhase.INGRESS, 2, "throw new Error('later must not run');");
        final var response = call("GET " + BASE + "/r");

        assertEquals(401, response.status());
        assertEquals("Bearer", response.headers().get("WWW-Authenticate"));
        assertEquals("{\"error\":\"unauthorized\"}", body(response));
    }

    @Test
    public void test_a_cache_hit_answers_200_without_the_target() throws Exception {
        expose("boom", "GET", "/r", "throw new Error('target must not run');");
        middleware("cache", MiddlewarePhase.INGRESS, 0, "reject(200, \"cached\");");
        final var response = call("GET " + BASE + "/r");

        assertEquals(200, response.status());
        assertEquals("cached", body(response));
    }

    @Test
    public void test_an_undecided_ingress_is_500_and_runs_nothing_else() throws Exception {
        expose("boom", "GET", "/r", "throw new Error('target must not run');");
        middleware("silent", MiddlewarePhase.INGRESS, 0, "const x = 1;");
        middleware("log", MiddlewarePhase.EGRESS, 0, "next({status: 299});");
        final var response = call("GET " + BASE + "/r");

        assertEquals(500, response.status());
        assertTrue(body(response).contains("runId"), body(response));
    }

    @Test
    public void test_a_failed_ingress_answers_its_failure_and_hides_the_message() throws Exception {
        expose("echo", "GET", "/r", ECHO);
        middleware("boom", MiddlewarePhase.INGRESS, 0, "throw new Error('secret detail');");
        final var response = call("GET " + BASE + "/r");

        assertEquals(500, response.status());
        assertFalse(body(response).contains("secret"));
        assertTrue(body(response).contains("runId"));
    }

    @Test
    public void test_an_invalid_reject_header_is_500() throws Exception {
        expose("echo", "GET", "/r", ECHO);
        middleware("bad", MiddlewarePhase.INGRESS, 0, "reject(401, null, {\"bad name\": \"x\"});");

        assertEquals(500, call("GET " + BASE + "/r").status());
    }

    @Test
    public void test_a_bad_change_fails_the_run() throws Exception {
        expose("echo", "GET", "/r", ECHO);
        middleware("typo", MiddlewarePhase.INGRESS, 0, "next({header: {a: \"1\"}});");

        assertEquals(500, call("GET " + BASE + "/r").status());
    }

    @Test
    public void test_match_and_exclude_choose_the_wrapped_entrypoints() throws Exception {
        expose("listOrders", "GET", "/orders", "return \"public\";");
        expose("getOrder", "GET", "/orders/{id}", "return \"private\";");
        expose("health", "GET", "/health", "return \"ok\";");
        final var auth = middlewareRequest("auth", MiddlewarePhase.INGRESS, 0, "reject(401);");
        auth.setMatch(MiddlewareSpecs.match(null, "/orders", null,
                List.of(new MiddlewareCriteriaSpec(null, null, List.of("listOrders")))));
        middleware(auth, ADMIN);

        assertEquals(200, call("GET " + BASE + "/orders").status());
        assertEquals(401, call("GET " + BASE + "/orders/o1").status());
        assertEquals(200, call("GET " + BASE + "/health").status());
    }

    @Test
    public void test_a_user_disabled_middleware_is_skipped() throws Exception {
        expose("echo", "GET", "/r", ECHO);
        final var request = middlewareRequest("off", MiddlewarePhase.INGRESS, 0, "reject(401);");
        request.setEnabled(false);
        middleware(request, ADMIN);

        assertEquals(200, call("GET " + BASE + "/r").status());
    }

    @Test
    public void test_a_revoked_middleware_fails_closed() throws Exception {
        expose("echo", "GET", "/r", ECHO);
        middleware("auth", MiddlewarePhase.INGRESS, 0, "next();");
        MiddlewareOperationHelper.disable(TestGlobals.PROJECT, cache.getMiddleware(TestGlobals.PROJECT, "auth"),
                "definer gone");

        final var response = call("GET " + BASE + "/r");
        assertEquals(500, response.status());
        assertEquals("{\"error\":\"Internal Server Error\"}", body(response));
    }

    @Test
    public void test_a_missing_middleware_procedure_fails_closed() throws Exception {
        expose("echo", "GET", "/r", ECHO);
        middleware("auth", MiddlewarePhase.INGRESS, 0, "next();");
        fs.metadataFiles().deleteProcedure(TestGlobals.PROJECT, "authMw");
        cache.removeProcedure(TestGlobals.PROJECT, "authMw");

        assertEquals(500, call("GET " + BASE + "/r").status());
    }

    @Test
    public void test_a_definer_losing_run_disables_the_middleware_and_fails_closed() throws Exception {
        expose("echo", "GET", "/r", ECHO);
        middleware(middlewareRequest("weak", MiddlewarePhase.INGRESS, 0, "next();"), POWERLESS);

        assertEquals(500, call("GET " + BASE + "/r").status());
        final var stored = cache.getMiddleware(TestGlobals.PROJECT, "weak");
        assertTrue(stored.isRevoked());
        assertTrue(stored.getDisabledReason().contains(POWERLESS), stored.getDisabledReason());
        assertEquals(500, call("GET " + BASE + "/r").status());
    }

    @Test
    public void test_each_middleware_runs_as_its_own_definer() throws Exception {
        expose("echo", "GET", "/r", ECHO);
        final var read = "db.findById(db.name, \"" + TestGlobals.COLL + "\", \"nobody\");\nnext();";
        final var asRunner = middlewareRequest("reader", MiddlewarePhase.INGRESS, 0, "");
        asRunner.setProcedure(new InlineProcedure("readerMw", "import db from \"db\";\n" + IMPORTS + read));
        middleware(asRunner, RUN_ONLY);

        assertEquals(500, call("GET " + BASE + "/r").status());
        middleware(asRunner, ADMIN);
        assertEquals(200, call("GET " + BASE + "/r").status());
    }

    @Test
    public void test_the_entrypoint_deadline_bounds_the_whole_chain() throws Exception {
        expose("echo", "GET", "/r", ECHO, 200L);
        middleware("slow", MiddlewarePhase.INGRESS, 0, "while (true) {}");
        middleware("log", MiddlewarePhase.EGRESS, 0, "next();");
        final var started = System.nanoTime();

        assertEquals(500, call("GET " + BASE + "/r").status());
        assertTrue(System.nanoTime() - started < 10_000_000_000L);
    }

    @Test
    public void test_a_middleware_timeout_caps_its_own_run() throws Exception {
        expose("echo", "GET", "/r", ECHO, 60_000L);
        final var request = middlewareRequest("slow", MiddlewarePhase.INGRESS, 0, "while (true) {}");
        request.setTimeoutMs(100L);
        middleware(request, ADMIN);
        final var started = System.nanoTime();

        assertEquals(500, call("GET " + BASE + "/r").status());
        assertTrue(System.nanoTime() - started < 10_000_000_000L);
    }
}
