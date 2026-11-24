package org.ccg.mpp.unit.ops.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.EntrypointDefinition;
import org.ccg.mpp.data.MiddlewareDefinition;
import org.ccg.mpp.data.MiddlewarePhase;
import org.ccg.mpp.data.admin.AdminUserEntry;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.MiddlewareOperationHelper;
import org.ccg.mpp.ops.ProcedureOperationHelper;
import org.ccg.mpp.ops.middleware.MiddlewareChains;
import org.ccg.mpp.ops.req.MiddlewareMatchSpec;
import org.ccg.mpp.ops.req.SaveMiddlewareRequest;
import org.ccg.mpp.ops.req.SaveProcedureRequest;
import org.ccg.mpp.test.MiddlewareSpecs;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class MiddlewareChainsTest {
    private static final String DEFINER = "mw_chains_admin";
    private static final String PROCEDURE = "noop";
    private static final EntrypointDefinition GET_ORDER = new EntrypointDefinition("getOrder", "GET", "/orders/{id}",
            "p", 0L, true, DEFINER, null, 1L, 0L, 0L, DEFINER);
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final MiddlewareChains chains = IocContainer.get(MiddlewareChains.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
        AdminOperationHelper
                .saveUserEntry(new AdminUserEntry(DEFINER, "h", true, Set.of(), new HashMap<>(), new HashMap<>()));
        ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.PROJECT, PROCEDURE, "return 1;"),
                DEFINER);
    }

    @AfterAll
    static void tearDown() throws Exception {
        IocContainer.get(MiddlewareChains.class).forgetProject(TestGlobals.PROJECT);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void reset() throws Exception {
        final var folder = middlewaresFolder();
        if (Files.isDirectory(folder)) {
            try (var files = Files.list(folder)) {
                for (final var file : files.toList()) {
                    Files.delete(file);
                }
            }
        }
        Files.deleteIfExists(folder);
        cache.removeMiddlewaresForProject(TestGlobals.PROJECT);
        chains.forgetProject(TestGlobals.PROJECT);
        chains.reload(TestGlobals.PROJECT);
    }

    private static Path middlewaresFolder() {
        return Path.of(TestGlobals.PATH, TestGlobals.PROJECT, Globals.MIDDLEWARES_FOLDER);
    }

    private static void save(String name, MiddlewarePhase phase, int order, boolean enabled,
            MiddlewareMatchSpec match) {
        final var request = new SaveMiddlewareRequest(TestGlobals.PROJECT, name, phase);
        request.setProcedureName(PROCEDURE);
        request.setOrder(order);
        request.setEnabled(enabled);
        request.setMatch(match);
        MiddlewareOperationHelper.executeSave(request, DEFINER);
    }

    private static List<String> names(List<MiddlewareDefinition> definitions) {
        return definitions.stream().map(MiddlewareDefinition::getName).toList();
    }

    @Test
    public void test_sorts_by_order_then_name_and_splits_phases() {
        save("zeta", MiddlewarePhase.INGRESS, 1, true, null);
        save("alpha", MiddlewarePhase.INGRESS, 1, true, null);
        save("first", MiddlewarePhase.INGRESS, -5, true, null);
        save("log", MiddlewarePhase.EGRESS, 9, true, null);
        save("cors", MiddlewarePhase.EGRESS, 2, true, null);
        final var chain = chains.chainFor(TestGlobals.PROJECT, GET_ORDER);

        assertEquals(List.of("first", "alpha", "zeta"), names(chain.ingress()));
        assertEquals(List.of("cors", "log"), names(chain.egress()));
        assertFalse(chain.isUnreadable());
    }

    @Test
    public void test_skips_user_disabled_and_keeps_revoked() throws Exception {
        save("off", MiddlewarePhase.INGRESS, 0, false, null);
        save("revoked", MiddlewarePhase.INGRESS, 1, true, null);
        MiddlewareOperationHelper.disable(TestGlobals.PROJECT, cache.getMiddleware(TestGlobals.PROJECT, "revoked"),
                "definer gone");

        assertEquals(List.of("revoked"), names(chains.chainFor(TestGlobals.PROJECT, GET_ORDER).ingress()));
        assertEquals(List.of("off", "revoked"), names(chains.sorted(TestGlobals.PROJECT)));
    }

    @Test
    public void test_filters_by_match() {
        save("orders", MiddlewarePhase.INGRESS, 0, true, MiddlewareSpecs.match(null, "/orders", null, null));
        save("users", MiddlewarePhase.INGRESS, 0, true, MiddlewareSpecs.match(null, "/users", null, null));

        assertEquals(List.of("orders"), names(chains.chainFor(TestGlobals.PROJECT, GET_ORDER).ingress()));
    }

    @Test
    public void test_an_unlistable_folder_keeps_the_previous_chain() throws Exception {
        save("auth", MiddlewarePhase.INGRESS, 0, true, null);
        fs.metadataFiles().deleteMiddleware(TestGlobals.PROJECT, "auth");
        Files.delete(middlewaresFolder());
        Files.writeString(middlewaresFolder(), "not a folder");
        chains.reload(TestGlobals.PROJECT);

        assertEquals(List.of("auth"), names(chains.chainFor(TestGlobals.PROJECT, GET_ORDER).ingress()));
    }

    @Test
    public void test_an_unlistable_folder_without_a_previous_chain_fails_closed() throws Exception {
        Files.writeString(middlewaresFolder(), "not a folder");
        chains.forgetProject(TestGlobals.PROJECT);
        chains.reload(TestGlobals.PROJECT);

        assertTrue(chains.chainFor(TestGlobals.PROJECT, GET_ORDER).isUnreadable());
        assertTrue(chains.sorted(TestGlobals.PROJECT).isEmpty());
    }

    @Test
    public void test_an_unreadable_definition_without_a_previous_copy_makes_the_project_unreadable() throws Exception {
        save("auth", MiddlewarePhase.INGRESS, 0, true, null);
        fs.metadataFiles().writeMiddleware(TestGlobals.PROJECT, "broken", "{not json");
        cache.removeMiddleware(TestGlobals.PROJECT, "broken");
        chains.reload(TestGlobals.PROJECT);

        final var chain = chains.chainFor(TestGlobals.PROJECT, GET_ORDER);
        assertTrue(chain.isUnreadable());
        assertTrue(chain.unreadableReason().contains("broken"), chain.unreadableReason());
        chains.reload(TestGlobals.PROJECT);
        assertTrue(chains.chainFor(TestGlobals.PROJECT, GET_ORDER).isUnreadable());
    }

    @Test
    public void test_an_unreadable_definition_keeps_its_previous_copy() throws Exception {
        save("auth", MiddlewarePhase.INGRESS, 0, true, null);
        fs.metadataFiles().writeMiddleware(TestGlobals.PROJECT, "auth", "{not json");
        cache.removeMiddleware(TestGlobals.PROJECT, "auth");
        chains.reload(TestGlobals.PROJECT);

        assertEquals(List.of("auth"), names(chains.chainFor(TestGlobals.PROJECT, GET_ORDER).ingress()));
    }

    @Test
    public void test_a_definition_without_a_phase_is_unreadable() throws Exception {
        fs.metadataFiles().writeMiddleware(TestGlobals.PROJECT, "nophase", "{\"name\":\"nophase\",\"version\":1}");
        cache.removeMiddleware(TestGlobals.PROJECT, "nophase");
        chains.forgetProject(TestGlobals.PROJECT);
        chains.reload(TestGlobals.PROJECT);

        assertTrue(chains.chainFor(TestGlobals.PROJECT, GET_ORDER).isUnreadable());
    }

    @Test
    public void test_forgetting_or_an_unknown_project_is_empty() {
        save("auth", MiddlewarePhase.INGRESS, 0, true, null);
        chains.forgetProject(TestGlobals.PROJECT);

        assertTrue(chains.chainFor(TestGlobals.PROJECT, GET_ORDER).ingress().isEmpty());
        assertTrue(chains.chainFor("noSuchProject", GET_ORDER).ingress().isEmpty());
        chains.reload("noSuchProject");
        chains.loadAll();
        assertEquals(List.of("auth"), names(chains.chainFor(TestGlobals.PROJECT, GET_ORDER).ingress()));
    }
}
