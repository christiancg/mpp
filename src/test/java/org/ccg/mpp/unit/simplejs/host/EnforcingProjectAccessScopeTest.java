package org.ccg.mpp.unit.simplejs.host;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

import java.util.List;
import java.util.UUID;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.conn.ClientTracker;
import org.ccg.mpp.data.admin.AdminCollEntry;
import org.ccg.mpp.ejson.elements.JsonArray;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.tx.TransactionRecovery;
import org.ccg.mpp.simplejs.SimpleJs;
import org.ccg.mpp.simplejs.exceptions.JsThrowException;
import org.ccg.mpp.simplejs.host.EnforcingProjectAccess;
import org.ccg.mpp.simplejs.host.ResourceLimits;
import org.ccg.mpp.simplejs.host.ScriptResult;
import org.ccg.mpp.simplejs.host.SimpleHostBindings;
import org.ccg.mpp.simplejs.values.JsObject;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class EnforcingProjectAccessScopeTest {
    private static final String ADMIN = "scopedadmin";
    private static final String UNPARSEABLE = "The command is not valid";
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
        TestUtils.createAdmin(ADMIN);
        IocContainer.get(FileSystem.class).createCollectionFile(TestGlobals.PROJECT,
                Globals.SCRIPT_RUNS_COLLECTION_NAME);
        AdminOperationHelper.createPageCollections(TestGlobals.PROJECT, Globals.SCRIPT_RUNS_COLLECTION_NAME);
        AdminOperationHelper
                .saveCollectionEntry(new AdminCollEntry(TestGlobals.PROJECT, Globals.SCRIPT_RUNS_COLLECTION_NAME));
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static JsonObject doc(String id) {
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        object.add("value", new JsonString("scoped"));
        return object;
    }

    private static JsonObject statusPatch() {
        final var patch = new JsonObject();
        patch.add("status", new JsonString("touched"));
        return patch;
    }

    private static JsonObject everything() {
        final var condition = new JsonObject();
        condition.add("fieldOperatorType", new JsonString("NOT_EQUALS"));
        condition.add("field", new JsonString("_id"));
        condition.add("value", new JsonString("none"));
        return condition;
    }

    private static EnforcingProjectAccess scoped() {
        return new EnforcingProjectAccess(ADMIN, null, TestGlobals.PROJECT);
    }

    @Test
    public void test_scoped_project_is_reported() {
        assertEquals(TestGlobals.PROJECT, scoped().scopedProject());
        assertNull(new EnforcingProjectAccess(ADMIN, null).scopedProject());
    }

    @Test
    public void test_access_within_the_scope_is_allowed() {
        final var db = scoped();
        db.save(TestGlobals.PROJECT, TestGlobals.COLL, doc("in-scope"));
        assertNotNull(db.findById(TestGlobals.PROJECT, TestGlobals.COLL, "in-scope"));
    }

    @Test
    public void test_access_to_another_project_throws() {
        final var db = scoped();
        final var error = assertThrows(JsThrowException.class, () -> db.findById("otherProject", "someColl", "x"));
        assertNotNull(error.getValue());
    }

    // Even an admin acting user cannot reach the admin project through a scoped script
    @Test
    public void test_access_to_admin_project_throws() {
        final var db = scoped();
        assertThrows(JsThrowException.class,
                () -> db.findById(Globals.ADMIN_PROJECT_NAME, Globals.ADMIN_USERS_COLLECTION_NAME, ADMIN));
    }

    @Test
    public void test_write_outside_the_scope_throws() {
        final var db = scoped();
        assertThrows(JsThrowException.class, () -> db.save("otherProject", TestGlobals.COLL, doc("nope")));
        assertThrows(JsThrowException.class, () -> db.insert("otherProject", TestGlobals.COLL, doc("nope")));
        assertThrows(JsThrowException.class, () -> db.delete("otherProject", TestGlobals.COLL, "nope"));
        assertThrows(JsThrowException.class, () -> db.deleteWhere("otherProject", TestGlobals.COLL, everything()));
        assertThrows(JsThrowException.class,
                () -> db.updateWhere("otherProject", TestGlobals.COLL, everything(), statusPatch()));
        assertThrows(JsThrowException.class, () -> db.listCollections("otherProject"));
    }

    @Test
    public void test_list_projects_returns_only_the_scope() {
        assertEquals(java.util.List.of(TestGlobals.PROJECT), scoped().listProjects());
    }

    @Test
    public void test_unscoped_instance_is_unrestricted() {
        final var db = new EnforcingProjectAccess(ADMIN, null);
        assertTrue(db.listProjects().contains(TestGlobals.PROJECT));
        assertNull(db.findById(TestGlobals.PROJECT, TestGlobals.COLL, "absent"));
    }

    @Test
    public void test_transaction_within_the_scope_commits() {
        final var db = scoped();
        db.beginTransaction();
        db.save(TestGlobals.PROJECT, TestGlobals.COLL, doc("tx-scoped"));
        db.commitTransaction();
        assertNotNull(db.findById(TestGlobals.PROJECT, TestGlobals.COLL, "tx-scoped"));
    }

    @Test
    public void test_a_script_cannot_write_to_script_runs() {
        final var db = scoped();

        assertThrows(JsThrowException.class,
                () -> db.save(TestGlobals.PROJECT, Globals.SCRIPT_RUNS_COLLECTION_NAME, doc("forged")),
                "script_runs is the audit trail, so a script with READ_WRITE must not be able to forge a row");
        assertThrows(JsThrowException.class,
                () -> db.delete(TestGlobals.PROJECT, Globals.SCRIPT_RUNS_COLLECTION_NAME, "forged"));
        assertThrows(JsThrowException.class,
                () -> db.deleteWhere(TestGlobals.PROJECT, Globals.SCRIPT_RUNS_COLLECTION_NAME, everything()));
        assertThrows(JsThrowException.class, () -> db.updateWhere(TestGlobals.PROJECT,
                Globals.SCRIPT_RUNS_COLLECTION_NAME, everything(), statusPatch()));
    }

    @Test
    public void test_a_script_cannot_write_an_invalid_id() {
        final var db = scoped();

        assertThrows(JsThrowException.class, () -> db.save(TestGlobals.PROJECT, TestGlobals.COLL, doc("has spaces!")),
                "an id the client API refuses would be unreachable through FIND_BY_ID and DELETE");
    }

    @Test
    public void test_a_script_can_still_read_script_runs() {
        final var db = scoped();

        assertDoesNotThrow(() -> db.findById(TestGlobals.PROJECT, Globals.SCRIPT_RUNS_COLLECTION_NAME, "any"));
    }

    private static org.ccg.mpp.ejson.elements.JsonArray pipeline(String... types) {
        final var steps = new org.ccg.mpp.ejson.elements.JsonArray();
        for (final var type : types) {
            final var step = new JsonObject();
            step.add("type", new JsonString(type));
            if ("SORT".equals(type)) {
                step.add("fieldName", new JsonString("_id"));
                step.add("ascending", org.ccg.mpp.ejson.elements.JsonBoolean.of(true));
            } else {
                step.add("limit", new org.ccg.mpp.ejson.elements.JsonNumber(5));
            }
            steps.add(step);
        }
        return steps;
    }

    @Test
    public void test_orders_results_reports_a_trailing_sort_as_ordered() {
        assertTrue(scoped().ordersResults(TestGlobals.PROJECT, TestGlobals.COLL, pipeline("SORT", "LIMIT")));
    }

    @Test
    public void test_orders_results_reports_a_bare_limit_as_unordered() {
        assertFalse(scoped().ordersResults(TestGlobals.PROJECT, TestGlobals.COLL, pipeline("LIMIT")));
    }

    @Test
    public void test_orders_results_leaves_an_unparseable_pipeline_to_aggregate() {
        final var steps = new org.ccg.mpp.ejson.elements.JsonArray();
        steps.add(new JsonString("not a step"));

        assertTrue(scoped().ordersResults(TestGlobals.PROJECT, TestGlobals.COLL, steps));
    }

    @Test
    public void test_first_unordered_cut_finds_a_bare_limit() {
        assertEquals(0, scoped().firstUnorderedCut(TestGlobals.PROJECT, TestGlobals.COLL, pipeline("LIMIT")));
    }

    @Test
    public void test_first_unordered_cut_is_none_after_a_sort() {
        assertEquals(-1, scoped().firstUnorderedCut(TestGlobals.PROJECT, TestGlobals.COLL, pipeline("SORT", "LIMIT")));
    }

    @Test
    public void test_first_unordered_cut_leaves_an_unparseable_pipeline_to_aggregate() {
        final var steps = new org.ccg.mpp.ejson.elements.JsonArray();
        steps.add(new JsonString("not a step"));

        assertEquals(-1, scoped().firstUnorderedCut(TestGlobals.PROJECT, TestGlobals.COLL, steps));
    }

    private static JsonArray pipelineOf(String stepType) {
        final var step = new JsonObject();
        step.add("type", new JsonString(stepType));
        final var pipeline = new JsonArray();
        pipeline.add(step);
        return pipeline;
    }

    private static ScriptResult run(String body) {
        final var source = "import db from 'db';\n" + body;
        return new SimpleJs().run(source, new SimpleHostBindings(new JsonObject(),
                new EnforcingProjectAccess(ADMIN, null), null, ResourceLimits.unlimited()));
    }

    @Test
    public void test_a_filter_step_without_an_operator_is_a_catchable_error() {
        final var db = new EnforcingProjectAccess(ADMIN, null);
        final var error = assertThrows(JsThrowException.class,
                () -> db.aggregate(TestGlobals.PROJECT, TestGlobals.COLL, pipelineOf("FILTER")));
        assertInstanceOf(JsObject.class, error.getValue());
    }

    @Test
    public void test_an_unknown_step_type_is_a_catchable_error() {
        final var db = new EnforcingProjectAccess(ADMIN, null);
        assertThrows(JsThrowException.class,
                () -> db.aggregate(TestGlobals.PROJECT, TestGlobals.COLL, pipelineOf("NOPE")));
    }

    @Test
    public void test_an_unparseable_pipeline_is_caught_inside_the_script() {
        final var result = run("try { db.aggregate('" + TestGlobals.PROJECT + "', '" + TestGlobals.COLL
                + "', [{ type: 'FILTER' }]); return 'returned'; } catch (e) { return e.name + ': ' + e.message; }");
        assertFalse(result.isError());
        assertEquals("Error: " + UNPARSEABLE, result.getValue().asJsonString().getValue());
    }

    @Test
    public void test_an_uncaught_unparseable_pipeline_is_not_an_internal_error() {
        final var result = run(
                "db.aggregate('" + TestGlobals.PROJECT + "', '" + TestGlobals.COLL + "', [{ type: 'NOPE' }]);");
        assertTrue(result.isError());
        assertEquals("Error", result.getErrorName());
        assertEquals(UNPARSEABLE, result.getErrorMessage());
    }

    @Test
    public void test_a_cursor_over_an_unparseable_pipeline_is_a_catchable_error() {
        final var result = run("try { for (const doc of db.cursor('" + TestGlobals.PROJECT + "', '" + TestGlobals.COLL
                + "', [{ type: 'FILTER' }])) { return doc; } return 'empty'; } catch (e) { return e.name; }");
        assertFalse(result.isError());
        assertEquals("Error", result.getValue().asJsonString().getValue());
    }

    private static UUID sessionClientIdOf(EnforcingProjectAccess project)
            throws NoSuchFieldException, IllegalAccessException {
        return TestUtils.getPrivateField(project, "sessionClientId", UUID.class);
    }

    private void assertKeptFencedAndCleanUp(UUID sessionClientId) throws Exception {
        final var transaction = clientTracker.getActiveTransaction(sessionClientId);
        assertNotNull(transaction, "the failed commit must not erase the Client that names the held locks");
        assertFalse(transaction.getHeldLocks().isEmpty(), "the collection stays fenced until recovery finishes");
        assertTrue(org.ccg.mpp.ops.tx.SliceStateMachine.isFenced(transaction.getTransactionId().toString()));

        AdminOperationHelper.deleteTransactionOps(List.copyOf(transaction.getBufferedOpIds()));
        TestUtils.releaseAllLocks();
        clientTracker.clearActiveTransaction(sessionClientId);
        clientTracker.clearTransactionState(sessionClientId);
        clientTracker.removeById(sessionClientId);
    }

    @Test
    public void test_a_throwable_past_the_commit_point_keeps_the_transaction_registered() throws Exception {
        final var project = new EnforcingProjectAccess(ADMIN, null);
        project.beginTransaction();
        final var sessionClientId = sessionClientIdOf(project);
        project.save(TestGlobals.PROJECT, TestGlobals.COLL, doc("fence-a"));

        try (var recovery = mockStatic(TransactionRecovery.class)) {
            recovery.when(() -> TransactionRecovery.applyAllWithRetry(anyList(), anyString(), any()))
                    .thenThrow(new OutOfMemoryError("simulated"));
            assertThrows(OutOfMemoryError.class, project::commitTransaction);
        }

        assertKeptFencedAndCleanUp(sessionClientId);
    }

    @Test
    public void test_an_exception_past_the_commit_point_is_still_correctly_fenced() throws Exception {
        final var project = new EnforcingProjectAccess(ADMIN, null);
        project.beginTransaction();
        final var sessionClientId = sessionClientIdOf(project);
        project.save(TestGlobals.PROJECT, TestGlobals.COLL, doc("fence-b"));

        try (var recovery = mockStatic(TransactionRecovery.class)) {
            recovery.when(() -> TransactionRecovery.applyAllWithRetry(anyList(), anyString(), any()))
                    .thenThrow(new RuntimeException("simulated"));
            assertThrows(JsThrowException.class, project::commitTransaction);
        }

        assertKeptFencedAndCleanUp(sessionClientId);
    }
}
