package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Set;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.ConfigKey;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.data.EntrypointDefinition;
import org.ccg.mpp.data.admin.AdminUserEntry;
import org.ccg.mpp.ejson.EJson;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.EntrypointOperationHelper;
import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.ProcedureOperationHelper;
import org.ccg.mpp.ops.admin.AdminStamp;
import org.ccg.mpp.ops.admin.StoredDefinitions;
import org.ccg.mpp.ops.entrypoint.EntrypointRouter;
import org.ccg.mpp.ops.entrypoint.RouteMatch;
import org.ccg.mpp.ops.req.DeleteEntrypointRequest;
import org.ccg.mpp.ops.req.DeleteProcedureRequest;
import org.ccg.mpp.ops.req.InlineProcedure;
import org.ccg.mpp.ops.req.ListEntrypointsRequest;
import org.ccg.mpp.ops.req.SaveEntrypointRequest;
import org.ccg.mpp.ops.req.SaveProcedureRequest;
import org.ccg.mpp.ops.resp.ListEntrypointsResponse;
import org.ccg.mpp.ops.resp.OperationResponse;
import org.ccg.mpp.ops.resp.SaveEntrypointResponse;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class EntrypointOperationHelperTest {
    private static final String DEFINER = "ep_alice";
    private static final String OTHER = "ep_bob";
    private static final String OWNER_ADMIN = "ep_root";
    private static final String PROCEDURE = "getOrder";
    private static final Configuration configuration = Configuration.getInstance();
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final EntrypointRouter router = IocContainer.get(EntrypointRouter.class);
    private final EJson eJson = IocContainer.get(EJson.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
        AdminOperationHelper.saveUserEntry(user(DEFINER, false));
        AdminOperationHelper.saveUserEntry(user(OTHER, false));
        AdminOperationHelper.saveUserEntry(user(OWNER_ADMIN, true));
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
        for (final var name : fs.metadataFiles().listProcedureNames(TestGlobals.PROJECT)) {
            fs.metadataFiles().deleteProcedure(TestGlobals.PROJECT, name);
        }
        cache.removeEntrypointsForProject(TestGlobals.PROJECT);
        cache.removeProceduresForProject(TestGlobals.PROJECT);
        router.reload(TestGlobals.PROJECT);
        ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.PROJECT, PROCEDURE, "return 1;"),
                DEFINER);
    }

    @AfterEach
    void restoreLimits() {
        configuration.override(ConfigKey.MAX_ENTRYPOINTS_PER_PROJECT,
                ConfigKey.MAX_ENTRYPOINTS_PER_PROJECT.defaultValue());
    }

    private static SaveEntrypointRequest byName(String name, String method, String path) {
        final var request = new SaveEntrypointRequest(TestGlobals.PROJECT, name, method, path);
        request.setProcedureName(PROCEDURE);
        return request;
    }

    private static SaveEntrypointRequest inline(String name, String procedureName, String script) {
        final var request = new SaveEntrypointRequest(TestGlobals.PROJECT, name, "POST", "/" + name);
        request.setProcedure(new InlineProcedure(procedureName, script));
        return request;
    }

    private static OperationResponse save(SaveEntrypointRequest request, String actor) throws Exception {
        return EntrypointOperationHelper.executeSave(request, actor);
    }

    private static String codeOf(OperationResponse response) {
        return response.getErrorCode();
    }

    private RouteMatch get(String... segments) {
        return router.table(TestGlobals.PROJECT).match("GET", List.of(segments));
    }

    @Test
    public void test_saves_and_reloads_router() throws Exception {
        final var response = assertInstanceOf(SaveEntrypointResponse.class,
                save(byName("read", "get", "/orders/{id}/"), DEFINER));

        assertEquals(1, response.getVersion());
        assertEquals(1, response.getProcedureVersion());
        final var stored = cache.getEntrypoint(TestGlobals.PROJECT, "read");
        assertEquals("GET", stored.getMethod());
        assertEquals("/orders/{id}", stored.getPath());
        assertEquals(DEFINER, stored.getDefiner());
        assertInstanceOf(RouteMatch.Found.class, get("orders", "o1"));
        assertTrue(AdminStamp.versionOf(StoredDefinitions.entrypoint(TestGlobals.PROJECT, "read")) > 0);
    }

    @Test
    public void test_unknown_procedure_is_404_8() throws Exception {
        final var request = byName("read", "GET", "/x");
        request.setProcedureName("missing");

        assertEquals(ErrorCode.PROCEDURE_NOT_FOUND.getCode(), codeOf(save(request, DEFINER)));
        assertNull(cache.getEntrypoint(TestGlobals.PROJECT, "read"));
    }

    @Test
    public void test_version_conflict_is_409_8() throws Exception {
        save(byName("read", "GET", "/x"), DEFINER);
        final var request = byName("read", "GET", "/x");
        request.setIfVersion(7L);

        assertEquals(ErrorCode.PROCEDURE_VERSION_CONFLICT.getCode(), codeOf(save(request, DEFINER)));
    }

    @Test
    public void test_cap_is_400_47() throws Exception {
        configuration.override(ConfigKey.MAX_ENTRYPOINTS_PER_PROJECT, "1");
        save(byName("one", "GET", "/one"), DEFINER);

        assertEquals(ErrorCode.TOO_MANY_ENTRYPOINTS.getCode(), codeOf(save(byName("two", "GET", "/two"), DEFINER)));
        assertEquals(OperationStatus.OK, save(byName("one", "GET", "/one"), DEFINER).getStatus());
    }

    @Test
    public void test_timeouts_are_checked() throws Exception {
        final var tooLong = byName("read", "GET", "/x");
        tooLong.setTimeoutMs(configuration.longValue(ConfigKey.ENTRYPOINT_MAX_TIMEOUT_MS) + 1);
        final var negative = byName("read", "GET", "/x");
        negative.setTimeoutMs(-1);

        assertEquals(ErrorCode.ENTRYPOINT_TIMEOUT_TOO_LARGE.getCode(), codeOf(save(tooLong, DEFINER)));
        assertEquals(ErrorCode.INVALID_ENTRYPOINT.getCode(), codeOf(save(negative, DEFINER)));
    }

    @Test
    public void test_bad_method_and_template_are_400_46() throws Exception {
        final var badMethod = save(byName("read", "HEAD", "/x"), DEFINER);
        final var badPath = save(byName("read", "GET", "/orders//x"), DEFINER);

        assertEquals(ErrorCode.INVALID_ENTRYPOINT.getCode(), codeOf(badMethod));
        assertTrue(badMethod.getMessage().contains("DELETE, GET, OPTIONS, PATCH, POST, PUT, QUERY"),
                badMethod.getMessage());
        assertEquals(ErrorCode.INVALID_ENTRYPOINT.getCode(), codeOf(badPath));
        assertTrue(badPath.getMessage().contains("empty segment"), badPath.getMessage());
    }

    @Test
    public void test_same_method_same_shape_is_409_19() throws Exception {
        save(byName("read", "GET", "/orders/{id}"), DEFINER);
        final var response = save(byName("dup", "GET", "/orders/{orderId}"), DEFINER);

        assertEquals(ErrorCode.ENTRYPOINT_ROUTE_CONFLICT.getCode(), codeOf(response));
        assertTrue(response.getMessage().contains("'read' (GET /orders/{id})"), response.getMessage());
    }

    @Test
    public void test_same_shape_other_method_and_literal_beside_param_are_allowed() throws Exception {
        save(byName("read", "GET", "/orders/{id}"), DEFINER);

        assertEquals(OperationStatus.OK, save(byName("remove", "DELETE", "/orders/{id}"), DEFINER).getStatus());
        assertEquals(OperationStatus.OK, save(byName("latest", "GET", "/orders/latest"), DEFINER).getStatus());
    }

    @Test
    public void test_replacing_itself_is_not_a_conflict() throws Exception {
        save(byName("read", "GET", "/orders/{id}"), DEFINER);
        final var response = assertInstanceOf(SaveEntrypointResponse.class,
                save(byName("read", "GET", "/orders/{orderId}"), DEFINER));

        assertEquals(2, response.getVersion());
    }

    @Test
    public void test_a_name_differing_only_by_case_collides() throws Exception {
        save(byName("read", "GET", "/a"), DEFINER);

        assertEquals(ErrorCode.NAME_COLLIDES_ON_DISK.getCode(), codeOf(save(byName("READ", "GET", "/b"), DEFINER)));
    }

    @Test
    public void test_inline_procedure_saved_as_its_own_procedure() throws Exception {
        final var response = assertInstanceOf(SaveEntrypointResponse.class,
                save(inline("create", "createOrder", "return {status: 201};"), DEFINER));

        assertEquals(1, response.getProcedureVersion());
        final var procedure = cache.getProcedure(TestGlobals.PROJECT, "createOrder");
        assertEquals("return {status: 201};", procedure.getSource());
        assertEquals("createOrder", cache.getEntrypoint(TestGlobals.PROJECT, "create").getProcedureName());
    }

    @Test
    public void test_inline_parse_error_writes_nothing() throws Exception {
        final var response = save(inline("create", "createOrder", "return {"), DEFINER);

        assertEquals(ErrorCode.INVALID_PROCEDURE.getCode(), codeOf(response));
        assertNull(cache.getProcedure(TestGlobals.PROJECT, "createOrder"));
        assertNull(cache.getEntrypoint(TestGlobals.PROJECT, "create"));
    }

    @Test
    public void test_inline_replaces_existing_procedure_and_bumps_version() throws Exception {
        final var response = assertInstanceOf(SaveEntrypointResponse.class,
                save(inline("read", PROCEDURE, "return 2;"), DEFINER));

        assertEquals(2, response.getProcedureVersion());
        assertEquals("return 2;", cache.getProcedure(TestGlobals.PROJECT, PROCEDURE).getSource());
    }

    @Test
    public void test_inline_bound_by_other_definer_is_403_4() throws Exception {
        save(byName("read", "GET", "/x"), DEFINER);

        assertEquals(ErrorCode.PROCEDURE_BOUND_BY_OTHER_DEFINER.getCode(),
                codeOf(save(inline("other", PROCEDURE, "return 3;"), OTHER)));
        assertEquals("return 1;", cache.getProcedure(TestGlobals.PROJECT, PROCEDURE).getSource());
    }

    @Test
    public void test_saver_becomes_definer_and_foreign_replace_is_403_5() throws Exception {
        save(byName("read", "GET", "/x"), DEFINER);

        assertEquals(ErrorCode.DEFINITION_OWNED_BY_OTHER_DEFINER.getCode(),
                codeOf(save(byName("read", "GET", "/x"), OTHER)));
        assertEquals(ErrorCode.DEFINITION_OWNED_BY_OTHER_DEFINER.getCode(), codeOf(EntrypointOperationHelper
                .executeDelete(new DeleteEntrypointRequest(TestGlobals.PROJECT, "read"), OTHER)));
        assertEquals(OperationStatus.OK, save(byName("read", "GET", "/x"), OWNER_ADMIN).getStatus());
        assertEquals(OWNER_ADMIN, cache.getEntrypoint(TestGlobals.PROJECT, "read").getDefiner());
    }

    @Test
    public void test_delete_is_ok_when_absent_and_removes_only_the_entrypoint() throws Exception {
        final var absent = new DeleteEntrypointRequest(TestGlobals.PROJECT, "never");
        assertEquals(OperationStatus.OK, EntrypointOperationHelper.executeDelete(absent, DEFINER).getStatus());
        save(byName("read", "GET", "/x"), DEFINER);

        assertEquals(OperationStatus.OK, EntrypointOperationHelper
                .executeDelete(new DeleteEntrypointRequest(TestGlobals.PROJECT, "read"), DEFINER).getStatus());
        assertNull(cache.getEntrypoint(TestGlobals.PROJECT, "read"));
        assertNull(fs.metadataFiles().readEntrypoint(TestGlobals.PROJECT, "read"));
        assertInstanceOf(RouteMatch.NoRoute.class, get("x"));
        assertNotNull(cache.getProcedure(TestGlobals.PROJECT, PROCEDURE));
    }

    @Test
    public void test_list_carries_url() throws Exception {
        save(byName("read", "GET", "/orders/{id}"), DEFINER);
        save(byName("home", "GET", "/"), DEFINER);
        final var listed = assertInstanceOf(ListEntrypointsResponse.class,
                EntrypointOperationHelper.executeList(new ListEntrypointsRequest(TestGlobals.PROJECT)));
        final var urls = listed.getEntrypoints().stream().map(json -> json.get("url").asJsonString().getValue())
                .sorted().toList();

        assertEquals(List.of("/" + TestGlobals.PROJECT, "/" + TestGlobals.PROJECT + "/orders/{id}"), urls);
    }

    @Test
    public void test_delete_procedure_refused_while_entrypoint_references_it() throws Exception {
        save(byName("read", "GET", "/x"), DEFINER);
        final var response = ProcedureOperationHelper
                .executeDelete(new DeleteProcedureRequest(TestGlobals.PROJECT, PROCEDURE), DEFINER);

        assertEquals(ErrorCode.INVALID_ENTRYPOINT.getCode(), codeOf(response));
        assertTrue(response.getMessage().contains("entrypoint 'read'"), response.getMessage());
    }

    @Test
    public void test_scripts_disabled_and_unknown_project_are_refused() throws Exception {
        final var unknown = new SaveEntrypointRequest("noSuchProject", "read", "GET", "/x");
        unknown.setProcedureName(PROCEDURE);
        assertEquals(ErrorCode.PROJECT_NOT_FOUND.getCode(), codeOf(save(unknown, DEFINER)));
        assertEquals(ErrorCode.PROJECT_NOT_FOUND.getCode(), codeOf(EntrypointOperationHelper
                .executeDelete(new DeleteEntrypointRequest("noSuchProject", "read"), DEFINER)));
        assertEquals(ErrorCode.PROJECT_NOT_FOUND.getCode(),
                codeOf(EntrypointOperationHelper.executeList(new ListEntrypointsRequest("noSuchProject"))));
        TestUtils.setPrivateField(configuration, "scriptsEnabled", false);

        assertEquals(ErrorCode.SCRIPTS_DISABLED.getCode(), codeOf(save(byName("read", "GET", "/x"), DEFINER)));
    }

    @Test
    public void test_disable_writes_a_disabled_copy_once() throws Exception {
        save(byName("read", "GET", "/x"), DEFINER);
        final var current = cache.getEntrypoint(TestGlobals.PROJECT, "read");

        assertTrue(EntrypointOperationHelper.disable(TestGlobals.PROJECT, current, "definer gone"));
        final var disabled = cache.getEntrypoint(TestGlobals.PROJECT, "read");
        assertEquals("definer gone", disabled.getDisabledReason());
        assertEquals(current.getVersion() + 1, disabled.getVersion());
        assertInstanceOf(RouteMatch.NoRoute.class, get("x"));
        assertFalse(EntrypointOperationHelper.disable(TestGlobals.PROJECT, current, "again"));
        assertEquals("read", EntrypointOperationHelper.entrypointReferencing(TestGlobals.PROJECT, PROCEDURE));
    }

    @Test
    public void test_process_dispatches_each_entrypoint_operation() {
        assertInstanceOf(SaveEntrypointResponse.class,
                EntrypointOperationHelper.process(byName("read", "GET", "/x"), DEFINER));
        assertInstanceOf(ListEntrypointsResponse.class,
                EntrypointOperationHelper.process(new ListEntrypointsRequest(TestGlobals.PROJECT), DEFINER));
        assertEquals(OperationStatus.OK, EntrypointOperationHelper
                .process(new DeleteEntrypointRequest(TestGlobals.PROJECT, "read"), DEFINER).getStatus());
        assertThrows(IllegalArgumentException.class, () -> EntrypointOperationHelper
                .process(new DeleteProcedureRequest(TestGlobals.PROJECT, PROCEDURE), DEFINER));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void test_forgetting_a_project_drops_its_unroutable_warnings() throws Exception {
        final var broken = new EntrypointDefinition("broken", "GET", "no-leading-slash", PROCEDURE, 0, true, DEFINER,
                null, 1, 1, 1, DEFINER);
        fs.metadataFiles().writeEntrypoint(TestGlobals.PROJECT, "broken", eJson.toJson(broken.toJsonObject()));
        cache.removeEntrypoint(TestGlobals.PROJECT, "broken");
        router.reload(TestGlobals.PROJECT);
        final Set<String> warned = TestUtils.getPrivateField(router, "warned", Set.class);
        final var prefix = TestGlobals.PROJECT + "|";
        assertTrue(warned.stream().anyMatch(key -> key.startsWith(prefix)), warned.toString());

        router.forgetProject(TestGlobals.PROJECT);

        assertTrue(warned.stream().noneMatch(key -> key.startsWith(prefix)), warned.toString());
        assertInstanceOf(RouteMatch.NoRoute.class, get("x"));
    }
}
