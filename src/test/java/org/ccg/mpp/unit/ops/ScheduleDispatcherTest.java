package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.ccg.mpp.bckg_ops.ScheduleExecutor;
import org.ccg.mpp.bckg_ops.ScheduleRegistry;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.data.ScheduleDefinition;
import org.ccg.mpp.data.admin.AdminCollEntry;
import org.ccg.mpp.ejson.EJson;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.CompiledProcedureCache;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.ProcedureOperationHelper;
import org.ccg.mpp.ops.ScheduleDispatcher;
import org.ccg.mpp.ops.UserOperationHelper;
import org.ccg.mpp.ops.admin.RestampedRecords;
import org.ccg.mpp.ops.req.CreateUserRequest;
import org.ccg.mpp.ops.req.DeleteUserRequest;
import org.ccg.mpp.ops.req.FindByIdRequest;
import org.ccg.mpp.ops.req.SaveProcedureRequest;
import org.ccg.mpp.ops.resp.FindByIdResponse;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ScheduleDispatcherTest {
    private static final String OWNER = "schedowner";
    private static final String OUTSIDER = "schedoutsider";
    private static final String OUTPUT_COLL = "schedOutput";
    private static final Configuration configuration = Configuration.getInstance();
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final EJson eJson = IocContainer.get(EJson.class);
    private final ScheduleRegistry registry = IocContainer.get(ScheduleRegistry.class);
    private final ScheduleExecutor scheduleExecutor = IocContainer.get(ScheduleExecutor.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
        IocContainer.get(FileSystem.class).createCollectionFile(TestGlobals.PROJECT, OUTPUT_COLL);
        AdminOperationHelper.createPageCollections(TestGlobals.PROJECT, OUTPUT_COLL);
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.PROJECT, OUTPUT_COLL));
        AdminOperationHelper.updateProjectOwners(TestGlobals.PROJECT, List.of(OWNER));
        createUser(OWNER);
        createUser(OUTSIDER);
    }

    private static void createUser(String username) {
        final var request = new CreateUserRequest();
        request.setUsername(username);
        request.setPassword("password123");
        request.setAdmin(false);
        request.setGlobalPermissions(new HashSet<>());
        request.setProjectPermissions(new HashMap<>());
        request.setCollectionPermissions(new HashMap<>());
        UserOperationHelper.processCreateUser(request);
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "schedulesEnabled", false);
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        IocContainer.get(ScheduleRegistry.class).clear();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void reset() throws Exception {
        TestUtils.setPrivateField(configuration, "schedulesEnabled", true);
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        TestUtils.setPrivateField(configuration, "scriptInstructionBudget", 10_000_000L);
        TestUtils.setPrivateField(configuration, "scriptTimeoutMs", 5_000L);
        TestUtils.setPrivateField(configuration, "scheduleTimeoutMs", 5_000L);
        TestUtils.setPrivateField(configuration, "scriptMaxDepth", 200);
        TestUtils.setPrivateField(configuration, "scriptMaxSourceBytes", 262_144L);
        TestUtils.setPrivateField(configuration, "scriptMaxMemoryBytes", 67_108_864L);
        TestUtils.setPrivateField(configuration, "scriptMaxResultBytes", 16_777_216L);
        TestUtils.setPrivateField(configuration, "scriptTimeZone", "UTC");
        TestUtils.setPrivateField(configuration, "procedureCacheSize", 128);
        for (final var name : fs.metadataFiles().listScheduleNames(TestGlobals.PROJECT)) {
            fs.metadataFiles().deleteSchedule(TestGlobals.PROJECT, name);
        }
        for (final var name : fs.metadataFiles().listProcedureNames(TestGlobals.PROJECT)) {
            fs.metadataFiles().deleteProcedure(TestGlobals.PROJECT, name);
        }
        cache.removeSchedulesForProject(TestGlobals.PROJECT);
        cache.removeProceduresForProject(TestGlobals.PROJECT);
        IocContainer.get(CompiledProcedureCache.class).invalidateProject(TestGlobals.PROJECT);
        registry.clear();
    }

    private void storeProcedure(String source) throws Exception {
        ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.PROJECT, "job", source), OWNER);
    }

    private void storeWritingProcedure() throws Exception {
        storeProcedure("import db from 'db'; import args from 'args';" + " db.save(db.name, '" + OUTPUT_COLL
                + "', { _id: args.id, ok: true });" + " return 'ok';");
    }

    private ScheduleRegistry.Entry register(String procedureName, String definer, boolean enabled, long timeoutMs,
            JsonObject args) throws Exception {
        final var definition = new ScheduleDefinition("s", procedureName, null, 60_000L, args, timeoutMs, enabled,
                definer, null, 1L, 1L, 1L, definer);
        fs.metadataFiles().writeSchedule(TestGlobals.PROJECT, "s", eJson.toJson(definition.toJsonObject()));
        cache.removeSchedule(TestGlobals.PROJECT, "s");
        registry.reload(TestGlobals.PROJECT);
        return registry.get(TestGlobals.PROJECT, "s");
    }

    private static JsonObject idArgs(String id) {
        final var args = new JsonObject();
        args.add("id", new JsonString(id));
        return args;
    }

    private JsonObject outputRow(String id) {
        final var request = new FindByIdRequest(TestGlobals.PROJECT, OUTPUT_COLL);
        request.set_id(id);
        final var response = IocContainer.get(OperationProcessor.class).processMessage(request);
        return response instanceof FindByIdResponse found ? found.getObject() : null;
    }

    @Test
    public void test_runs_with_definer_authority() throws Exception {
        storeWritingProcedure();
        ScheduleDispatcher.dispatch(register("job", OWNER, true, 0L, idArgs("by-definer")));
        assertNotNull(outputRow("by-definer"));
        assertTrue(Objects.requireNonNull(outputRow("by-definer")).get("ok").asJsonBoolean().getValue());
    }

    @Test
    public void test_is_bounded_by_the_definers_authority() throws Exception {
        storeWritingProcedure();
        final var before = scheduleExecutor.getSkipped();
        ScheduleDispatcher.dispatch(register("job", OUTSIDER, true, 0L, idArgs("by-outsider")));
        assertNull(outputRow("by-outsider"));
        assertEquals(before + 1, scheduleExecutor.getSkipped());
        final var stored = cache.getSchedule(TestGlobals.PROJECT, "s");
        assertFalse(stored.isEnabled(), "a definer without script RUN disables the schedule");
        assertTrue(stored.getDisabledReason().contains("no longer holds script RUN"));
    }

    // Deliberately no fallback to an admin: that would let deleting a user widen a job's authority.
    @Test
    public void test_a_missing_definer_disables_the_schedule() throws Exception {
        createUser("tempdefiner");
        storeWritingProcedure();
        final var entry = register("job", "tempdefiner", true, 0L, idArgs("no-definer"));
        final var request = new DeleteUserRequest();
        request.setUsername("tempdefiner");
        UserOperationHelper.processDeleteUser(request, new RestampedRecords());

        final var before = scheduleExecutor.getSkipped();
        ScheduleDispatcher.dispatch(entry);
        assertNull(outputRow("no-definer"));
        assertEquals(before + 1, scheduleExecutor.getSkipped());
    }

    @Test
    public void test_a_missing_procedure_is_counted_and_skipped() throws Exception {
        final var entry = register("nope", OWNER, true, 0L, idArgs("missing-procedure"));
        final var before = scheduleExecutor.getSkipped();
        assertDoesNotThrow(() -> ScheduleDispatcher.dispatch(entry));
        assertEquals(before + 1, scheduleExecutor.getSkipped());
    }

    @Test
    public void test_a_disabled_procedure_is_counted_and_skipped() throws Exception {
        storeWritingProcedure();
        final var procedure = cache.getProcedure(TestGlobals.PROJECT, "job");
        final var disabled = new org.ccg.mpp.data.ProcedureDefinition(procedure.getName(), procedure.getSource(),
                procedure.getVersion(), null, false, 1L, 1L, OWNER);
        cache.putProcedure(TestGlobals.PROJECT, disabled);
        final var entry = register("job", OWNER, true, 0L, idArgs("disabled-procedure"));
        final var before = scheduleExecutor.getSkipped();
        ScheduleDispatcher.dispatch(entry);
        assertNull(outputRow("disabled-procedure"));
        assertEquals(before + 1, scheduleExecutor.getSkipped());
    }

    @Test
    public void test_a_disabled_schedule_is_counted_and_skipped() throws Exception {
        storeWritingProcedure();
        final var entry = register("job", OWNER, false, 0L, idArgs("disabled-schedule"));
        final var before = scheduleExecutor.getSkipped();
        ScheduleDispatcher.dispatch(entry);
        assertNull(outputRow("disabled-schedule"));
        assertEquals(before + 1, scheduleExecutor.getSkipped());
    }

    @Test
    public void test_a_script_error_is_counted_as_a_failure() throws Exception {
        storeProcedure("throw new Error('boom');");
        final var entry = register("job", OWNER, true, 0L, new JsonObject());
        final var before = scheduleExecutor.getFailed();
        assertDoesNotThrow(() -> ScheduleDispatcher.dispatch(entry));
        assertEquals(before + 1, scheduleExecutor.getFailed());
    }

    @Test
    public void test_per_schedule_timeout_overrides_the_default() throws Exception {
        TestUtils.setPrivateField(configuration, "scheduleTimeoutMs", 60_000L);
        storeProcedure("while (true) { }");
        final var entry = register("job", OWNER, true, 50L, new JsonObject());
        final var before = scheduleExecutor.getFailed();
        final var start = System.currentTimeMillis();
        ScheduleDispatcher.dispatch(entry);
        final var elapsed = System.currentTimeMillis() - start;
        assertEquals(before + 1, scheduleExecutor.getFailed());
        assertTrue(elapsed < 30_000L, "the schedule's own timeoutMs must bound the run, but it took " + elapsed + "ms");
    }

    // Unlike a trigger, a scheduled run is not already inside a transaction, so it may open its own.
    @Test
    public void test_a_scheduled_run_may_open_its_own_transaction() throws Exception {
        storeProcedure("import db from 'db'; import args from 'args';" + " db.transaction(() => { db.save(db.name, '"
                + OUTPUT_COLL + "', { _id: args.id, ok: true }); });" + " return 'ok';");
        ScheduleDispatcher.dispatch(register("job", OWNER, true, 0L, idArgs("in-transaction")));
        assertNotNull(outputRow("in-transaction"));
    }

    @Test
    public void test_scheduled_runs_are_not_result_capped() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptMaxResultBytes", 256L);
        storeProcedure("import db from 'db'; import args from 'args';" + " db.save(db.name, '" + OUTPUT_COLL
                + "', { _id: args.id, ok: true });" + " return new Array(5000).fill('0123456789');");
        ScheduleDispatcher.dispatch(register("job", OWNER, true, 0L, idArgs("big-result")));
        assertNotNull(outputRow("big-result"), "the run must complete despite its oversized result");
    }

    @Test
    public void test_a_run_queued_before_its_schedule_was_deleted_does_not_fire() throws Exception {
        storeWritingProcedure();
        final var queued = register("job", OWNER, true, 0L, idArgs("deleted-while-queued"));
        fs.metadataFiles().deleteSchedule(TestGlobals.PROJECT, "s");
        cache.removeSchedule(TestGlobals.PROJECT, "s");
        registry.reload(TestGlobals.PROJECT);

        final var before = scheduleExecutor.getSkipped();
        ScheduleDispatcher.dispatch(queued);
        assertNull(outputRow("deleted-while-queued"));
        assertEquals(before + 1, scheduleExecutor.getSkipped());
    }

    @Test
    public void test_a_run_queued_before_its_schedule_was_replaced_does_not_fire() throws Exception {
        storeWritingProcedure();
        final var queued = register("job", OWNER, true, 0L, idArgs("stale-args"));
        final var current = register("job", OWNER, true, 0L, idArgs("current-args"));

        ScheduleDispatcher.dispatch(queued);
        assertNull(outputRow("stale-args"));
        ScheduleDispatcher.dispatch(current);
        assertNotNull(outputRow("current-args"));
    }

    @Test
    public void test_an_unchanged_reload_keeps_a_queued_run_valid() throws Exception {
        storeWritingProcedure();
        final var queued = register("job", OWNER, true, 0L, idArgs("unchanged-reload"));
        cache.removeSchedule(TestGlobals.PROJECT, "s");
        registry.reload(TestGlobals.PROJECT);

        assertSame(queued, registry.get(TestGlobals.PROJECT, "s"));
        ScheduleDispatcher.dispatch(queued);
        assertNotNull(outputRow("unchanged-reload"));
    }
}
