package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.ccg.mpp.data.admin.AdminUserEntry;
import org.ccg.mpp.data.auth.PermissionLevel;
import org.ccg.mpp.data.auth.ScriptPermissionLevel;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.ProcedureOperationHelper;
import org.ccg.mpp.ops.req.ListProceduresRequest;
import org.ccg.mpp.ops.req.SaveProcedureRequest;
import org.ccg.mpp.ops.resp.ListProceduresResponse;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ProcedureListingTest {
    private static final String RUNNER = "listing_runner";
    private static final String MANAGER = "listing_manager";

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
        saveUser(RUNNER, ScriptPermissionLevel.RUN);
        saveUser(MANAGER, ScriptPermissionLevel.MANAGE);
        ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.PROJECT, "listed", "return 1;"),
                MANAGER);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static void saveUser(String name, ScriptPermissionLevel scriptLevel) throws Exception {
        AdminOperationHelper.saveUserEntry(new AdminUserEntry(name, "h", false, Set.of(),
                new HashMap<>(Map.of(TestGlobals.PROJECT, PermissionLevel.READ)), new HashMap<>(),
                new HashMap<>(Map.of(TestGlobals.PROJECT, scriptLevel))));
    }

    private static JsonObject onlySummaryFor(String actingUser) {
        final var response = assertInstanceOf(ListProceduresResponse.class,
                ProcedureOperationHelper.executeList(new ListProceduresRequest(TestGlobals.PROJECT), actingUser));
        assertEquals(1, response.getProcedures().size());
        return response.getProcedures().getFirst();
    }

    @Test
    public void runOnlyCallerGetsNoSourceHash() {
        final var summary = onlySummaryFor(RUNNER);
        assertFalse(summary.has("sourceHash"));
        assertFalse(summary.has("source"));
        assertTrue(summary.has("version"));
    }

    @Test
    public void managerAndInternalCallerKeepTheSourceHash() {
        assertTrue(onlySummaryFor(MANAGER).has("sourceHash"));
        assertTrue(onlySummaryFor(null).has("sourceHash"));
    }
}
