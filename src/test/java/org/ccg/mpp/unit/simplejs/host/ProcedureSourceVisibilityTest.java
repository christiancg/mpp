package org.ccg.mpp.unit.simplejs.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import org.ccg.mpp.data.admin.AdminUserEntry;
import org.ccg.mpp.data.auth.PermissionLevel;
import org.ccg.mpp.data.auth.ScriptPermissionLevel;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.ProcedureOperationHelper;
import org.ccg.mpp.ops.req.SaveProcedureRequest;
import org.ccg.mpp.simplejs.SimpleJs;
import org.ccg.mpp.simplejs.host.EnforcingProjectAccess;
import org.ccg.mpp.simplejs.host.ProcedureModuleResolver;
import org.ccg.mpp.simplejs.host.ProjectHostBindings;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class ProcedureSourceVisibilityTest {
    private static final String SOURCE = "export function double(n) { return n * 2; }";
    private static final String RUNNER = "srcrunner";
    private static final String MANAGER = "srcmanager";
    private static final String IMPORT_AND_SHOW = "import { double } from 'procedures/srclib';"
            + " return [String(double), double(21)].join('|');";

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
        saveUser(RUNNER, ScriptPermissionLevel.RUN);
        saveUser(MANAGER, ScriptPermissionLevel.MANAGE);
        ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.PROJECT, "srclib", SOURCE), MANAGER);
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static void saveUser(String name, ScriptPermissionLevel level) throws Exception {
        AdminOperationHelper.saveUserEntry(new AdminUserEntry(name, "unused", false, new HashSet<>(),
                new HashMap<>(Map.of(TestGlobals.PROJECT, PermissionLevel.READ_WRITE)), new HashMap<>(),
                new HashMap<>(Map.of(TestGlobals.PROJECT, level))));
    }

    private static String runAs(String username) {
        final var access = new EnforcingProjectAccess(username, null, TestGlobals.PROJECT);
        final var result = new SimpleJs().run(IMPORT_AND_SHOW,
                ProjectHostBindings.of(null, access, null, ProjectHostBindings.limitsFromConfiguration(), null));
        return result.getValue().asJsonString().getValue();
    }

    @Test
    public void test_an_imported_procedure_function_to_string_is_a_native_stub_for_run_only() {
        assertEquals("function double() { [native code] }|42", runAs(RUNNER));
    }

    @Test
    public void test_a_manage_holder_sees_the_imported_source() {
        assertEquals("function double(n) { return n * 2; }|42", runAs(MANAGER));
    }

    @Test
    public void test_the_caller_decides_whether_the_source_is_readable() {
        assertFalse(new EnforcingProjectAccess(RUNNER, null, TestGlobals.PROJECT).mayReadProcedureSource());
        assertTrue(new EnforcingProjectAccess(MANAGER, null, TestGlobals.PROJECT).mayReadProcedureSource());
        assertFalse(new EnforcingProjectAccess(MANAGER, null, null).mayReadProcedureSource());
        assertFalse(new EnforcingProjectAccess("no-such-user", null, TestGlobals.PROJECT).mayReadProcedureSource());
    }

    @Test
    public void test_a_hidden_resolution_carries_no_shared_parse() {
        final var hidden = Objects.requireNonNull(
                new ProcedureModuleResolver(TestGlobals.PROJECT, false).resolve("procedures/srclib", "main"));
        assertTrue(hidden.sourceHidden());
        assertNull(hidden.compiled());
        final var revealed = Objects
                .requireNonNull(new ProcedureModuleResolver(TestGlobals.PROJECT).resolve("procedures/srclib", "main"));
        assertFalse(revealed.sourceHidden());
    }
}
