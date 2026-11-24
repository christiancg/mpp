package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.ConfigKey;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.data.MiddlewareDefinition;
import org.ccg.mpp.data.MiddlewarePhase;
import org.ccg.mpp.data.admin.AdminUserEntry;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.MiddlewareOperationHelper;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.ProcedureOperationHelper;
import org.ccg.mpp.ops.admin.AdminStamp;
import org.ccg.mpp.ops.admin.StoredDefinitions;
import org.ccg.mpp.ops.middleware.MiddlewareChains;
import org.ccg.mpp.ops.req.DeleteMiddlewareRequest;
import org.ccg.mpp.ops.req.DeleteProcedureRequest;
import org.ccg.mpp.ops.req.InlineProcedure;
import org.ccg.mpp.ops.req.ListMiddlewaresRequest;
import org.ccg.mpp.ops.req.MiddlewareCriteriaSpec;
import org.ccg.mpp.ops.req.MiddlewareMatchSpec;
import org.ccg.mpp.ops.req.SaveMiddlewareRequest;
import org.ccg.mpp.ops.req.SaveProcedureRequest;
import org.ccg.mpp.ops.resp.ListMiddlewaresResponse;
import org.ccg.mpp.ops.resp.OperationResponse;
import org.ccg.mpp.ops.resp.SaveMiddlewareResponse;
import org.ccg.mpp.test.MiddlewareSpecs;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class MiddlewareOperationHelperTest {
    private static final String DEFINER = "mw_alice";
    private static final String OTHER = "mw_bob";
    private static final String OWNER_ADMIN = "mw_root";
    private static final String PROCEDURE = "checkToken";
    private static final Configuration configuration = Configuration.getInstance();
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final MiddlewareChains chains = IocContainer.get(MiddlewareChains.class);

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
        IocContainer.get(MiddlewareChains.class).forgetProject(TestGlobals.PROJECT);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static AdminUserEntry user(String name, boolean admin) {
        return new AdminUserEntry(name, "h", admin, Set.of(), new HashMap<>(), new HashMap<>());
    }

    @BeforeEach
    void reset() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        for (final var name : fs.metadataFiles().listMiddlewareNames(TestGlobals.PROJECT)) {
            fs.metadataFiles().deleteMiddleware(TestGlobals.PROJECT, name);
        }
        for (final var name : fs.metadataFiles().listProcedureNames(TestGlobals.PROJECT)) {
            fs.metadataFiles().deleteProcedure(TestGlobals.PROJECT, name);
        }
        cache.removeMiddlewaresForProject(TestGlobals.PROJECT);
        cache.removeProceduresForProject(TestGlobals.PROJECT);
        chains.reload(TestGlobals.PROJECT);
        ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.PROJECT, PROCEDURE, "return 1;"),
                DEFINER);
    }

    @AfterEach
    void restoreLimits() {
        configuration.override(ConfigKey.MAX_MIDDLEWARES_PER_PROJECT,
                ConfigKey.MAX_MIDDLEWARES_PER_PROJECT.defaultValue());
    }

    private static SaveMiddlewareRequest byName(String name, MiddlewarePhase phase) {
        final var request = new SaveMiddlewareRequest(TestGlobals.PROJECT, name, phase);
        request.setProcedureName(PROCEDURE);
        return request;
    }

    private static SaveMiddlewareRequest matching(MiddlewareMatchSpec match) {
        final var request = byName("auth", MiddlewarePhase.INGRESS);
        request.setMatch(match);
        return request;
    }

    private static OperationResponse save(SaveMiddlewareRequest request, String actor) {
        return MiddlewareOperationHelper.executeSave(request, actor);
    }

    private static void assertInvalid(OperationResponse response, String fragment) {
        assertEquals(ErrorCode.INVALID_MIDDLEWARE.getCode(), response.getErrorCode(), response.getMessage());
        assertTrue(response.getMessage().contains(fragment), response.getMessage());
    }

    @Test
    public void test_saves_by_name_and_reloads_the_chains() throws Exception {
        final var request = byName("auth", MiddlewarePhase.INGRESS);
        request.setOrder(10);
        request.setMatch(MiddlewareSpecs.match(List.of("post", "POST"), "/orders/", null, null));
        final var response = assertInstanceOf(SaveMiddlewareResponse.class, save(request, DEFINER));

        assertEquals(1, response.getVersion());
        assertEquals(1, response.getProcedureVersion());
        final var stored = cache.getMiddleware(TestGlobals.PROJECT, "auth");
        assertEquals(MiddlewarePhase.INGRESS, stored.getPhase());
        assertEquals(10, stored.getOrder());
        assertEquals(List.of("POST"), stored.getMatch().include().methods());
        assertEquals("/orders", stored.getMatch().include().pathPrefix());
        assertEquals(DEFINER, stored.getDefiner());
        assertEquals(List.of("auth"),
                chains.sorted(TestGlobals.PROJECT).stream().map(MiddlewareDefinition::getName).toList());
        assertTrue(AdminStamp.versionOf(StoredDefinitions.middleware(TestGlobals.PROJECT, "auth")) > 0);
    }

    @Test
    public void test_saving_again_bumps_the_version_and_honours_if_version() {
        save(byName("auth", MiddlewarePhase.INGRESS), DEFINER);
        final var second = assertInstanceOf(SaveMiddlewareResponse.class,
                save(byName("auth", MiddlewarePhase.EGRESS), DEFINER));
        final var conflicting = byName("auth", MiddlewarePhase.EGRESS);
        conflicting.setIfVersion(7L);

        assertEquals(2, second.getVersion());
        assertEquals(MiddlewarePhase.EGRESS, cache.getMiddleware(TestGlobals.PROJECT, "auth").getPhase());
        assertEquals(ErrorCode.PROCEDURE_VERSION_CONFLICT.getCode(), save(conflicting, DEFINER).getErrorCode());
    }

    @Test
    public void test_inline_procedure_is_saved_as_its_own_procedure() {
        final var request = new SaveMiddlewareRequest(TestGlobals.PROJECT, "cors", MiddlewarePhase.EGRESS);
        request.setProcedure(new InlineProcedure("cors", "import { next } from \"middleware\";\nnext();"));
        final var response = assertInstanceOf(SaveMiddlewareResponse.class, save(request, DEFINER));

        assertEquals(1, response.getProcedureVersion());
        assertNotNull(cache.getProcedure(TestGlobals.PROJECT, "cors"));
        assertEquals("cors", cache.getMiddleware(TestGlobals.PROJECT, "cors").getProcedureName());
    }

    @Test
    public void test_a_bad_phase_is_400_49() {
        assertInvalid(save(byName("auth", null), DEFINER), "phase must be INGRESS or EGRESS");
    }

    @Test
    public void test_a_bad_method_or_a_parameter_prefix_is_400_49() {
        assertInvalid(save(matching(MiddlewareSpecs.match(List.of("HEAD"), null, null, null)), DEFINER),
                "match.methods holds 'HEAD'");
        assertInvalid(save(matching(MiddlewareSpecs.match(null, "/{tenant}", null, null)), DEFINER),
                "match.pathPrefix is not valid");
        assertInvalid(save(matching(MiddlewareSpecs.match(null, null, List.of("a b"), null)), DEFINER),
                "match.entrypoints holds 'a b'");
    }

    @Test
    public void test_an_unconstrained_exclude_rule_is_400_49() {
        final var rules = List.of(new MiddlewareCriteriaSpec());
        assertInvalid(save(matching(MiddlewareSpecs.match(null, "/orders", null, rules)), DEFINER),
                "match.exclude[0] needs at least one");
        final var nullRule = new ArrayList<MiddlewareCriteriaSpec>();
        nullRule.add(null);
        assertInvalid(save(matching(MiddlewareSpecs.match(null, null, null, nullRule)), DEFINER),
                "match.exclude[0] must be an object");
    }

    @Test
    public void test_more_than_sixteen_exclude_rules_is_400_49() {
        final var rules = new ArrayList<MiddlewareCriteriaSpec>();
        for (var i = 0; i < 17; i++) {
            rules.add(new MiddlewareCriteriaSpec(null, null, List.of("entry" + i)));
        }
        assertInvalid(save(matching(MiddlewareSpecs.match(null, null, null, rules)), DEFINER), "more than 16 rules");
    }

    @Test
    public void test_a_bad_method_or_prefix_in_an_exclude_rule_is_400_49() {
        final var badMethod = List.of(new MiddlewareCriteriaSpec(List.of("TRACE"), null, null));
        final var badPrefix = List.of(new MiddlewareCriteriaSpec(null, "orders", null));
        assertInvalid(save(matching(MiddlewareSpecs.match(null, null, null, badMethod)), DEFINER),
                "match.exclude[0].methods");
        assertInvalid(save(matching(MiddlewareSpecs.match(null, null, null, badPrefix)), DEFINER),
                "match.exclude[0].pathPrefix");
    }

    @Test
    public void test_an_exclude_naming_a_missing_entrypoint_is_accepted() {
        final var rules = List.of(new MiddlewareCriteriaSpec(null, null, List.of("notYetSaved")));
        save(matching(MiddlewareSpecs.match(null, "/orders", null, rules)), DEFINER);

        assertEquals(List.of("notYetSaved"),
                cache.getMiddleware(TestGlobals.PROJECT, "auth").getMatch().exclude().getFirst().entrypoints());
    }

    @Test
    public void test_negative_and_too_large_timeouts_are_400_49() {
        final var negative = byName("auth", MiddlewarePhase.INGRESS);
        negative.setTimeoutMs(-1);
        final var tooLarge = byName("auth", MiddlewarePhase.INGRESS);
        tooLarge.setTimeoutMs(configuration.longValue(ConfigKey.ENTRYPOINT_MAX_TIMEOUT_MS) + 1);

        assertInvalid(save(negative, DEFINER), "must not be negative");
        assertInvalid(save(tooLarge, DEFINER), "entrypointMaxTimeoutMs");
    }

    @Test
    public void test_the_cap_is_400_50() {
        configuration.override(ConfigKey.MAX_MIDDLEWARES_PER_PROJECT, "1");
        save(byName("one", MiddlewarePhase.INGRESS), DEFINER);

        assertEquals(ErrorCode.TOO_MANY_MIDDLEWARES.getCode(),
                save(byName("two", MiddlewarePhase.INGRESS), DEFINER).getErrorCode());
        assertEquals(OperationStatus.OK, save(byName("one", MiddlewarePhase.EGRESS), DEFINER).getStatus());
    }

    @Test
    public void test_a_name_differing_only_by_case_collides() {
        save(byName("auth", MiddlewarePhase.INGRESS), DEFINER);

        assertEquals(ErrorCode.NAME_COLLIDES_ON_DISK.getCode(),
                save(byName("AUTH", MiddlewarePhase.INGRESS), DEFINER).getErrorCode());
    }

    @Test
    public void test_saver_becomes_definer_and_foreign_replace_or_delete_is_403_5() throws Exception {
        save(byName("auth", MiddlewarePhase.INGRESS), DEFINER);

        assertEquals(ErrorCode.DEFINITION_OWNED_BY_OTHER_DEFINER.getCode(),
                save(byName("auth", MiddlewarePhase.INGRESS), OTHER).getErrorCode());
        assertEquals(ErrorCode.DEFINITION_OWNED_BY_OTHER_DEFINER.getCode(), MiddlewareOperationHelper
                .executeDelete(new DeleteMiddlewareRequest(TestGlobals.PROJECT, "auth"), OTHER).getErrorCode());
        assertEquals(OperationStatus.OK, save(byName("auth", MiddlewarePhase.INGRESS), OWNER_ADMIN).getStatus());
        assertEquals(OWNER_ADMIN, cache.getMiddleware(TestGlobals.PROJECT, "auth").getDefiner());
    }

    @Test
    public void test_scripts_disabled_unknown_project_and_unknown_procedure_are_refused() throws Exception {
        final var unknownProject = new SaveMiddlewareRequest("noSuchProject", "auth", MiddlewarePhase.INGRESS);
        unknownProject.setProcedureName(PROCEDURE);
        final var unknownProcedure = byName("auth", MiddlewarePhase.INGRESS);
        unknownProcedure.setProcedureName("missing");

        assertEquals(ErrorCode.PROJECT_NOT_FOUND.getCode(), save(unknownProject, DEFINER).getErrorCode());
        assertEquals(ErrorCode.PROCEDURE_NOT_FOUND.getCode(), save(unknownProcedure, DEFINER).getErrorCode());
        assertEquals(ErrorCode.PROJECT_NOT_FOUND.getCode(), MiddlewareOperationHelper
                .executeDelete(new DeleteMiddlewareRequest("noSuchProject", "auth"), DEFINER).getErrorCode());
        assertEquals(ErrorCode.PROJECT_NOT_FOUND.getCode(),
                MiddlewareOperationHelper.executeList(new ListMiddlewaresRequest("noSuchProject")).getErrorCode());
        TestUtils.setPrivateField(configuration, "scriptsEnabled", false);

        assertEquals(ErrorCode.SCRIPTS_DISABLED.getCode(),
                save(byName("auth", MiddlewarePhase.INGRESS), DEFINER).getErrorCode());
    }

    @Test
    public void test_delete_is_idempotent_tombstones_and_keeps_the_procedure() throws Exception {
        assertEquals(OperationStatus.OK, MiddlewareOperationHelper
                .executeDelete(new DeleteMiddlewareRequest(TestGlobals.PROJECT, "never"), DEFINER).getStatus());
        save(byName("auth", MiddlewarePhase.INGRESS), DEFINER);

        assertEquals(OperationStatus.OK, MiddlewareOperationHelper
                .executeDelete(new DeleteMiddlewareRequest(TestGlobals.PROJECT, "auth"), DEFINER).getStatus());
        assertNull(cache.getMiddleware(TestGlobals.PROJECT, "auth"));
        assertNull(fs.metadataFiles().readMiddleware(TestGlobals.PROJECT, "auth"));
        assertTrue(chains.sorted(TestGlobals.PROJECT).isEmpty());
        assertNotNull(cache.getProcedure(TestGlobals.PROJECT, PROCEDURE));
    }

    @Test
    public void test_list_is_in_execution_order() {
        final var late = byName("late", MiddlewarePhase.INGRESS);
        late.setOrder(20);
        final var tieB = byName("tieB", MiddlewarePhase.INGRESS);
        tieB.setOrder(5);
        final var tieA = byName("tieA", MiddlewarePhase.INGRESS);
        tieA.setOrder(5);
        final var after = byName("after", MiddlewarePhase.EGRESS);
        after.setOrder(-3);
        for (final var request : List.of(late, after, tieB, tieA)) {
            save(request, DEFINER);
        }
        final var listed = assertInstanceOf(ListMiddlewaresResponse.class,
                MiddlewareOperationHelper.executeList(new ListMiddlewaresRequest(TestGlobals.PROJECT)));

        assertEquals(List.of("tieA", "tieB", "late", "after"),
                listed.getMiddlewares().stream().map(json -> json.get("name").asJsonString().getValue()).toList());
    }

    @Test
    public void test_delete_procedure_refused_while_a_middleware_references_it() throws Exception {
        save(byName("auth", MiddlewarePhase.INGRESS), DEFINER);
        final var response = ProcedureOperationHelper
                .executeDelete(new DeleteProcedureRequest(TestGlobals.PROJECT, PROCEDURE), DEFINER);

        assertEquals(ErrorCode.INVALID_MIDDLEWARE.getCode(), response.getErrorCode());
        assertTrue(response.getMessage().contains("middleware 'auth'"), response.getMessage());
        assertEquals("auth", MiddlewareOperationHelper.middlewareReferencing(TestGlobals.PROJECT, PROCEDURE));
        assertNull(MiddlewareOperationHelper.middlewareReferencing(TestGlobals.PROJECT, "other"));
    }

    @Test
    public void test_disable_writes_a_disabled_copy_only_for_the_matching_version() throws Exception {
        save(byName("auth", MiddlewarePhase.INGRESS), DEFINER);
        final var current = cache.getMiddleware(TestGlobals.PROJECT, "auth");

        assertTrue(MiddlewareOperationHelper.disable(TestGlobals.PROJECT, current, "definer gone"));
        final var disabled = cache.getMiddleware(TestGlobals.PROJECT, "auth");
        assertTrue(disabled.isRevoked());
        assertEquals(current.getVersion() + 1, disabled.getVersion());
        assertTrue(chains.sorted(TestGlobals.PROJECT).getFirst().isRevoked());
        assertFalse(MiddlewareOperationHelper.disable(TestGlobals.PROJECT, current, "again"));
        assertFalse(MiddlewareOperationHelper.disable(TestGlobals.PROJECT, disabled, "again"));
    }

    @Test
    public void test_process_dispatches_each_middleware_operation() {
        assertInstanceOf(SaveMiddlewareResponse.class,
                MiddlewareOperationHelper.process(byName("auth", MiddlewarePhase.INGRESS), DEFINER));
        assertInstanceOf(ListMiddlewaresResponse.class,
                MiddlewareOperationHelper.process(new ListMiddlewaresRequest(TestGlobals.PROJECT), DEFINER));
        assertEquals(OperationStatus.OK, MiddlewareOperationHelper
                .process(new DeleteMiddlewareRequest(TestGlobals.PROJECT, "auth"), DEFINER).getStatus());
        assertThrows(IllegalArgumentException.class, () -> MiddlewareOperationHelper
                .process(new DeleteProcedureRequest(TestGlobals.PROJECT, PROCEDURE), DEFINER));
    }
}
