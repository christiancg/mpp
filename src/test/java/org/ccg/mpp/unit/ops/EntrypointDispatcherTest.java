package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Set;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.data.EntrypointDefinition;
import org.ccg.mpp.data.admin.AdminUserEntry;
import org.ccg.mpp.ejson.EJson;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.http.HttpRequest;
import org.ccg.mpp.http.HttpRequestHead;
import org.ccg.mpp.http.HttpResponse;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.EntrypointDispatcher;
import org.ccg.mpp.ops.EntrypointOperationHelper;
import org.ccg.mpp.ops.ScriptAdmission;
import org.ccg.mpp.ops.admin.StoredDefinitions;
import org.ccg.mpp.ops.entrypoint.EntrypointRouter;
import org.ccg.mpp.ops.middleware.MiddlewareChains;
import org.ccg.mpp.ops.req.InlineProcedure;
import org.ccg.mpp.ops.req.SaveEntrypointRequest;
import org.ccg.mpp.ops.resp.SaveEntrypointResponse;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class EntrypointDispatcherTest {
    private static final String DEFINER = "ep_dispatch_admin";
    private static final String POWERLESS = "ep_dispatch_nobody";
    private static final String BASE = "/" + TestGlobals.PROJECT;
    private static final Configuration configuration = Configuration.getInstance();
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final EntrypointRouter router = IocContainer.get(EntrypointRouter.class);
    private final MiddlewareChains chains = IocContainer.get(MiddlewareChains.class);
    private final EJson eJson = IocContainer.get(EJson.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
        AdminOperationHelper.saveUserEntry(user(DEFINER, true));
        AdminOperationHelper.saveUserEntry(user(POWERLESS, false));
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        IocContainer.get(EntrypointRouter.class).forgetProject(TestGlobals.PROJECT);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static AdminUserEntry user(String name, boolean admin) {
        return new AdminUserEntry(name, "h", admin, Set.of(), new HashMap<>(), new HashMap<>());
    }

    @BeforeEach
    void reset() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        for (final var name : fs.metadataFiles().listEntrypointNames(TestGlobals.PROJECT)) {
            fs.metadataFiles().deleteEntrypoint(TestGlobals.PROJECT, name);
        }
        for (final var name : fs.metadataFiles().listMiddlewareNames(TestGlobals.PROJECT)) {
            fs.metadataFiles().deleteMiddleware(TestGlobals.PROJECT, name);
        }
        cache.removeEntrypointsForProject(TestGlobals.PROJECT);
        cache.removeMiddlewaresForProject(TestGlobals.PROJECT);
        router.reload(TestGlobals.PROJECT);
        chains.reload(TestGlobals.PROJECT);
    }

    private static void expose(String name, String method, String path, String script, String definer)
            throws Exception {
        final var request = new SaveEntrypointRequest(TestGlobals.PROJECT, name, method, path);
        request.setProcedure(new InlineProcedure(name + "Proc", script));
        assertInstanceOf(SaveEntrypointResponse.class, EntrypointOperationHelper.executeSave(request, definer));
    }

    private static void expose(String name, String method, String path, String script) throws Exception {
        expose(name, method, path, script, DEFINER);
    }

    private static HttpResponse call(String requestLine, String headers, String body) throws IOException {
        final var text = requestLine + " HTTP/1.1\r\n" + headers + "\r\n";
        final var head = HttpRequestHead.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.ISO_8859_1)),
                4096);
        final var bytes = body.getBytes(StandardCharsets.UTF_8);
        return EntrypointDispatcher.handle(new HttpRequest(head, "127.0.0.1", bytes.length, () -> bytes));
    }

    private static HttpResponse call(String requestLine) throws IOException {
        return call(requestLine, "", "");
    }

    private static String body(HttpResponse response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    @Test
    public void test_runs_procedure_with_args() throws Exception {
        expose("echo", "POST", "/orders/{id}", "import args from \"args\";\nreturn args;");
        final var response = call("POST " + BASE + "/orders/o%2F1?x=1", "Content-Type: application/json\r\n",
                "{\"qty\":2}");
        final var args = eJson.fromJson(body(response), JsonObject.class);

        assertEquals(200, response.status());
        assertEquals("o/1", args.get("pathParams").asJsonObject().get("id").asJsonString().getValue());
        assertEquals("/orders/o%2F1", args.get("path").asJsonString().getValue());
        assertEquals(2.0, args.get("body").asJsonObject().get("qty").asJsonNumber().doubleValue());
        assertEquals("echo", args.get("entrypoint").asJsonString().getValue());
    }

    @Test
    public void test_the_run_uses_the_definers_authority() throws Exception {
        expose("save", "POST", "/docs", "import db from \"db\";\nimport args from \"args\";\n"
                + "return db.save(db.name, \"" + TestGlobals.COLL + "\", args.body);");
        final var response = call("POST " + BASE + "/docs", "Content-Type: application/json\r\n",
                "{\"_id\":\"fromHttp\"}");

        assertEquals(200, response.status());
        assertTrue(body(response).contains("fromHttp"));
    }

    @Test
    public void test_unknown_project_route_and_root_are_404() throws Exception {
        expose("read", "GET", "/orders/{id}", "return 1;");

        assertEquals(404, call("GET /noSuchProject/orders/o1").status());
        assertEquals(404, call("GET " + BASE + "/users/u1").status());
        assertEquals(404, call("GET /").status());
        assertEquals(404, call("GET " + BASE + "//orders").status());
    }

    @Test
    public void test_trailing_slash_and_project_root_route() throws Exception {
        expose("home", "GET", "/", "return 'home';");
        expose("read", "GET", "/orders/{id}", "return 'order';");

        assertEquals("home", body(call("GET " + BASE)));
        assertEquals("home", body(call("GET " + BASE + "/")));
        assertEquals("order", body(call("GET " + BASE + "/orders/o1/")));
    }

    @Test
    public void test_wrong_method_is_405_with_allow() throws Exception {
        expose("read", "GET", "/orders/{id}", "return 1;");
        expose("remove", "DELETE", "/orders/{id}", "return 1;");
        final var response = call("PUT " + BASE + "/orders/o1");

        assertEquals(405, response.status());
        assertEquals("DELETE, GET", response.headers().get("Allow"));
    }

    @Test
    public void test_a_malformed_path_is_400() throws IOException {
        assertEquals(400, call("GET " + BASE + "/orders/%zz").status());
    }

    @Test
    public void test_disabled_entrypoint_is_404() throws Exception {
        final var request = new SaveEntrypointRequest(TestGlobals.PROJECT, "off", "GET", "/off");
        request.setProcedure(new InlineProcedure("offProc", "return 1;"));
        request.setEnabled(false);
        EntrypointOperationHelper.executeSave(request, DEFINER);

        assertEquals(404, call("GET " + BASE + "/off").status());
    }

    @Test
    public void test_definer_without_run_disables_and_answers_404() throws Exception {
        expose("weak", "GET", "/weak", "return 1;", POWERLESS);

        assertEquals(404, call("GET " + BASE + "/weak").status());
        final var stored = cache.getEntrypoint(TestGlobals.PROJECT, "weak");
        assertFalse(stored.isEnabled());
        assertNotNull(stored.getDisabledReason());
        assertTrue(stored.getDisabledReason().contains(POWERLESS), stored.getDisabledReason());
    }

    @Test
    public void test_scripts_disabled_is_503() throws Exception {
        expose("read", "GET", "/r", "return 1;");
        TestUtils.setPrivateField(configuration, "scriptsEnabled", false);

        assertEquals(503, call("GET " + BASE + "/r").status());
    }

    @Test
    public void test_concurrency_limit_is_503_with_retry_after() throws Exception {
        expose("read", "GET", "/r", "return 1;");
        final var admission = IocContainer.get(ScriptAdmission.class);
        admission.reconfigure(0, 0L, 1, 0);
        try {
            final var held = assertInstanceOf(ScriptAdmission.Permit.class,
                    admission.acquire(DEFINER, TestGlobals.PROJECT, false));
            try (held) {
                final var response = call("GET " + BASE + "/r");

                assertEquals(503, response.status());
                assertEquals("1", response.headers().get("Retry-After"));
            }
            assertEquals(200, call("GET " + BASE + "/r").status());
        } finally {
            admission.reconfigure(configuration.getMaxConcurrentScripts(), configuration.getScriptQueueWaitMs(),
                    configuration.getMaxConcurrentScriptsPerUser(), configuration.getMaxConcurrentScriptsPerProject());
        }
    }

    @Test
    public void test_malformed_body_is_400_without_running() throws Exception {
        expose("save", "POST", "/docs", "import db from \"db\";\nreturn db.save(db.name, \"" + TestGlobals.COLL
                + "\", {_id: \"neverSaved\"});");

        assertEquals(400, call("POST " + BASE + "/docs", "Content-Type: application/json\r\n", "{bad").status());
    }

    @Test
    public void test_a_body_that_never_arrives_is_408_and_closes() throws Exception {
        expose("save", "POST", "/docs", "return 1;");
        final var text = "POST " + BASE + "/docs HTTP/1.1\r\nContent-Length: 5\r\n\r\n";
        final var head = HttpRequestHead.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.ISO_8859_1)),
                4096);
        final var response = EntrypointDispatcher.handle(new HttpRequest(head, "127.0.0.1", 5, () -> {
            throw new EOFException("gone");
        }));

        assertEquals(408, response.status());
        assertTrue(response.closes());
    }

    @Test
    public void test_a_throwing_procedure_is_500_and_a_slow_one_times_out() throws Exception {
        expose("boom", "GET", "/boom", "throw new Error('secret');");
        final var slow = new SaveEntrypointRequest(TestGlobals.PROJECT, "slow", "GET", "/slow");
        slow.setProcedure(new InlineProcedure("slowProc", "while (true) {}"));
        slow.setTimeoutMs(100);
        EntrypointOperationHelper.executeSave(slow, DEFINER);

        final var boom = call("GET " + BASE + "/boom");
        assertEquals(500, boom.status());
        assertFalse(body(boom).contains("secret"));
        final var started = System.nanoTime();
        assertEquals(500, call("GET " + BASE + "/slow").status());
        assertTrue(System.nanoTime() - started < 10_000_000_000L);
    }

    @Test
    public void test_stale_match_with_moved_version_is_rerouted() throws Exception {
        expose("moving", "GET", "/before", "return 1;");
        final var current = cache.getEntrypoint(TestGlobals.PROJECT, "moving");
        final var moved = new EntrypointDefinition("moving", "GET", "/after", current.getProcedureName(), 0, true,
                DEFINER, null, current.getVersion() + 1, current.getCreatedAt(), current.getUpdatedAt(), DEFINER);
        StoredDefinitions.writeEntrypoint(TestGlobals.PROJECT, "moving", moved.toJsonObject());
        cache.putEntrypoint(TestGlobals.PROJECT, moved);

        assertEquals(404, call("GET " + BASE + "/before").status());
        assertEquals(200, call("GET " + BASE + "/after").status());
    }

    @Test
    public void test_a_missing_procedure_is_500() throws Exception {
        expose("orphan", "GET", "/orphan", "return 1;");
        fs.metadataFiles().deleteProcedure(TestGlobals.PROJECT, "orphanProc");
        cache.removeProcedure(TestGlobals.PROJECT, "orphanProc");

        assertEquals(500, call("GET " + BASE + "/orphan").status());
    }
}
