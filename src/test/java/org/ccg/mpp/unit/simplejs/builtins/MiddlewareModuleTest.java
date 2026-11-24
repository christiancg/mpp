package org.ccg.mpp.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.function.Consumer;
import org.ccg.mpp.data.MiddlewarePhase;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.simplejs.SimpleJs;
import org.ccg.mpp.simplejs.host.HostBindings;
import org.ccg.mpp.simplejs.host.MiddlewareDecision;
import org.ccg.mpp.simplejs.host.ProjectAccess;
import org.ccg.mpp.simplejs.host.ResourceLimits;
import org.ccg.mpp.simplejs.host.ScriptResult;
import org.junit.jupiter.api.Test;

public class MiddlewareModuleTest {
    private static final SimpleJs simpleJs = IocContainer.get(SimpleJs.class);
    private static final String IMPORT = "import { next, reject } from \"middleware\";\n";

    private record Bindings(MiddlewareDecision middlewareDecision) implements HostBindings {
        @Override
        public JsonObject args() {
            return new JsonObject();
        }

        @Override
        public ProjectAccess project() {
            return null;
        }

        @Override
        public Consumer<String> console() {
            return _ -> {
            };
        }

        @Override
        public ResourceLimits limits() {
            return new ResourceLimits(-1, 30_000L, 200, 8L * 1024 * 1024);
        }
    }

    private static MiddlewareDecision ingress() {
        return new MiddlewareDecision(MiddlewarePhase.INGRESS, Set.of("id"));
    }

    private static MiddlewareDecision egress() {
        return new MiddlewareDecision(MiddlewarePhase.EGRESS, Set.of());
    }

    private static ScriptResult run(MiddlewareDecision decision, String body) {
        return simpleJs.run(IMPORT + body, new Bindings(decision));
    }

    private static void assertTypeError(ScriptResult result, String fragment) {
        assertTrue(result.isError(), "expected a TypeError");
        assertEquals("TypeError", result.getErrorName(), result.getErrorMessage());
        assertTrue(result.getErrorMessage().contains(fragment), result.getErrorMessage());
    }

    @Test
    public void test_next_records_changes() {
        final var decision = ingress();
        final var result = run(decision,
                "next({headers: {\"X-A\": \"1\", b: null}, query: {q: [\"x\", \"y\"]}, pathParams: {id: \"O1\"},"
                        + " body: 7, locals: {user: \"u\"}});");

        assertFalse(result.isError(), result.getErrorMessage());
        final var next = assertInstanceOf(MiddlewareDecision.Outcome.Next.class, decision.outcome());
        assertEquals("O1", next.changes().get("pathParams").asJsonObject().get("id").asJsonString().getValue());
        assertEquals(7.0, next.changes().get("body").asJsonNumber().doubleValue());
        assertTrue(next.changes().get("headers").asJsonObject().get("b").isJsonNull());
    }

    @Test
    public void test_reject_records_an_envelope() {
        final var decision = ingress();
        final var result = run(decision, "reject(401, {error: \"no\"}, {\"www-authenticate\": \"Bearer\"});");

        assertFalse(result.isError(), result.getErrorMessage());
        final var reject = assertInstanceOf(MiddlewareDecision.Outcome.Reject.class, decision.outcome());
        assertEquals(401, reject.status());
        assertEquals("no", reject.body().asJsonObject().get("error").asJsonString().getValue());
        assertEquals("Bearer", reject.headers().get("www-authenticate").asJsonString().getValue());
    }

    @Test
    public void test_reject_with_only_a_status_has_no_body_or_headers() {
        final var decision = egress();
        assertFalse(run(decision, "reject(204);").isError());
        final var reject = assertInstanceOf(MiddlewareDecision.Outcome.Reject.class, decision.outcome());
        assertTrue(reject.body().isJsonNull());
        assertTrue(reject.headers().isEmpty());
    }

    @Test
    public void test_a_second_decision_is_a_type_error() {
        final var decision = ingress();
        assertTypeError(run(decision, "next();\nreject(401);"), "already called");
        assertInstanceOf(MiddlewareDecision.Outcome.Next.class, decision.outcome());
    }

    @Test
    public void test_reject_refuses_a_non_integer_or_out_of_range_status() {
        assertTypeError(run(ingress(), "reject(200.5);"), "integer status");
        assertTypeError(run(ingress(), "reject(99);"), "integer status");
        assertTypeError(run(ingress(), "reject(600);"), "integer status");
        assertTypeError(run(ingress(), "reject(\"401\");"), "integer status");
    }

    @Test
    public void test_each_phase_refuses_the_other_phases_keys() {
        assertTypeError(run(ingress(), "next({status: 201});"), "'status'");
        assertTypeError(run(egress(), "next({query: {a: \"1\"}});"), "'query'");
        assertTypeError(run(egress(), "next({pathParams: {id: \"1\"}});"), "'pathParams'");
    }

    @Test
    public void test_routing_and_caller_keys_are_refused() {
        for (final var key : new String[]{"method", "path", "entrypoint", "remoteAddress"}) {
            assertTypeError(run(ingress(), "next({" + key + ": \"x\"});"), "'" + key + "'");
        }
    }

    @Test
    public void test_an_unknown_key_is_named_in_the_type_error() {
        assertTypeError(run(ingress(), "next({header: {a: \"1\"}});"), "does not accept 'header'");
    }

    @Test
    public void test_path_params_refuse_an_undeclared_name_and_null() {
        assertTypeError(run(ingress(), "next({pathParams: {other: \"1\"}});"), "declares no parameter 'other'");
        assertTypeError(run(ingress(), "next({pathParams: {id: null}});"), "must be a string");
    }

    @Test
    public void test_query_takes_a_string_an_array_of_strings_or_null() {
        assertFalse(run(ingress(), "next({query: {a: \"1\", b: [\"x\"], c: null}});").isError());
        assertTypeError(run(ingress(), "next({query: {a: 1}});"), "query");
        assertTypeError(run(ingress(), "next({query: {a: [1]}});"), "query");
    }

    @Test
    public void test_header_values_must_be_strings() {
        assertTypeError(run(ingress(), "next({headers: {a: 1}});"), "must be a string or null");
        assertTypeError(run(egress(), "reject(400, null, {a: null});"), "must be a string");
        assertTypeError(run(ingress(), "next({headers: [\"a\"]});"), "must be an object");
    }

    @Test
    public void test_egress_status_must_be_in_range() {
        assertTypeError(run(egress(), "next({status: 42});"), "integer status");
        assertFalse(run(egress(), "next({status: 202, body: \"done\"});").isError());
    }

    @Test
    public void test_changes_must_be_an_object() {
        assertTypeError(run(ingress(), "next(5);"), "must be an object");
        assertTypeError(run(ingress(), "next({locals: 5});"), "must be an object");
    }

    @Test
    public void test_the_return_value_is_ignored() {
        final var decision = ingress();
        assertFalse(run(decision, "return {status: 500, body: \"ignored\"};").isError());
        assertNull(decision.outcome());
    }

    @Test
    public void test_a_bare_next_without_return_counts() {
        final var decision = egress();
        assertFalse(run(decision, "next();\nconst after = 1;").isError());
        final var next = assertInstanceOf(MiddlewareDecision.Outcome.Next.class, decision.outcome());
        assertTrue(next.changes().isEmpty());
    }

    @Test
    public void test_the_module_does_not_exist_outside_a_middleware_run() {
        final var result = simpleJs.run(IMPORT + "next();", new Bindings(null));

        assertTrue(result.isError());
        assertTrue(result.getErrorMessage().contains("Cannot find module 'middleware'"), result.getErrorMessage());
    }
}
