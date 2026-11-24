package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.data.MiddlewarePhase;
import org.ccg.mpp.data.admin.AdminUserEntry;
import org.ccg.mpp.data.auth.ScriptPermissionLevel;
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
import org.ccg.mpp.ops.MiddlewareOperationHelper;
import org.ccg.mpp.ops.entrypoint.EntrypointRouter;
import org.ccg.mpp.ops.middleware.MiddlewareChains;
import org.ccg.mpp.ops.req.InlineProcedure;
import org.ccg.mpp.ops.req.SaveEntrypointRequest;
import org.ccg.mpp.ops.req.SaveMiddlewareRequest;
import org.ccg.mpp.ops.resp.SaveEntrypointResponse;
import org.ccg.mpp.ops.resp.SaveMiddlewareResponse;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

abstract class MiddlewareHttpFixture {
    static final String ADMIN = "mw_http_admin";
    static final String RUN_ONLY = "mw_http_runner";
    static final String POWERLESS = "mw_http_nobody";
    static final String BASE = "/" + TestGlobals.PROJECT;
    static final String ECHO = "import args from \"args\";\nreturn args;";
    static final String IMPORTS = "import args from \"args\";\nimport { next, reject } from \"middleware\";\n";
    static final Configuration configuration = Configuration.getInstance();
    final Cache cache = IocContainer.get(Cache.class);
    final FileSystem fs = IocContainer.get(FileSystem.class);
    final EJson eJson = IocContainer.get(EJson.class);

    @BeforeAll
    static void setUpUsers() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
        AdminOperationHelper
                .saveUserEntry(new AdminUserEntry(ADMIN, "h", true, Set.of(), new HashMap<>(), new HashMap<>()));
        AdminOperationHelper.saveUserEntry(new AdminUserEntry(RUN_ONLY, "h", false, Set.of(), new HashMap<>(),
                new HashMap<>(), new HashMap<>(Map.of(TestGlobals.PROJECT, ScriptPermissionLevel.MANAGE))));
        AdminOperationHelper
                .saveUserEntry(new AdminUserEntry(POWERLESS, "h", false, Set.of(), new HashMap<>(), new HashMap<>()));
    }

    @AfterAll
    static void tearDownProject() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        IocContainer.get(EntrypointRouter.class).forgetProject(TestGlobals.PROJECT);
        IocContainer.get(MiddlewareChains.class).forgetProject(TestGlobals.PROJECT);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void clearDefinitions() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        for (final var name : fs.metadataFiles().listEntrypointNames(TestGlobals.PROJECT)) {
            fs.metadataFiles().deleteEntrypoint(TestGlobals.PROJECT, name);
        }
        for (final var name : fs.metadataFiles().listMiddlewareNames(TestGlobals.PROJECT)) {
            fs.metadataFiles().deleteMiddleware(TestGlobals.PROJECT, name);
        }
        cache.removeEntrypointsForProject(TestGlobals.PROJECT);
        cache.removeMiddlewaresForProject(TestGlobals.PROJECT);
        IocContainer.get(EntrypointRouter.class).reload(TestGlobals.PROJECT);
        IocContainer.get(MiddlewareChains.class).reload(TestGlobals.PROJECT);
    }

    static void expose(String name, String method, String path, String script, long timeoutMs) throws Exception {
        final var request = new SaveEntrypointRequest(TestGlobals.PROJECT, name, method, path);
        request.setProcedure(new InlineProcedure(name + "Proc", script));
        request.setTimeoutMs(timeoutMs);
        assertInstanceOf(SaveEntrypointResponse.class, EntrypointOperationHelper.executeSave(request, ADMIN));
    }

    static void expose(String name, String method, String path, String script) throws Exception {
        expose(name, method, path, script, 0L);
    }

    static SaveMiddlewareRequest middlewareRequest(String name, MiddlewarePhase phase, int order, String script) {
        final var request = new SaveMiddlewareRequest(TestGlobals.PROJECT, name, phase);
        request.setOrder(order);
        request.setProcedure(new InlineProcedure(name + "Mw", IMPORTS + script));
        return request;
    }

    static void middleware(SaveMiddlewareRequest request, String definer) {
        assertInstanceOf(SaveMiddlewareResponse.class, MiddlewareOperationHelper.executeSave(request, definer));
    }

    static void middleware(String name, MiddlewarePhase phase, int order, String script) {
        middleware(middlewareRequest(name, phase, order, script), ADMIN);
    }

    static HttpResponse call(String requestLine, String headers, String body) throws IOException {
        final var text = requestLine + " HTTP/1.1\r\n" + headers + "\r\n";
        final var head = HttpRequestHead.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.ISO_8859_1)),
                4096);
        final var bytes = body.getBytes(StandardCharsets.UTF_8);
        return EntrypointDispatcher.handle(new HttpRequest(head, "127.0.0.1", bytes.length, () -> bytes));
    }

    static HttpResponse call(String requestLine) throws IOException {
        return call(requestLine, "", "");
    }

    static String body(HttpResponse response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    JsonObject json(HttpResponse response) {
        return eJson.fromJson(body(response), JsonObject.class);
    }

    static String text(JsonObject object, String key) {
        return object.get(key).asJsonString().getValue();
    }
}
