package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.data.TriggerDefinition;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.ProcedureOperationHelper;
import org.ccg.mpp.ops.TriggerOperationHelper;
import org.ccg.mpp.ops.req.SaveProcedureRequest;
import org.ccg.mpp.ops.req.SaveTriggerRequest;
import org.ccg.mpp.ops.resp.SaveTriggerResponse;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TriggerOperationHelperBeforeTimingTest {
    private static final String ACTOR = "alice";
    private static final Configuration configuration = Configuration.getInstance();
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void reset() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        fs.metadataFiles().deleteTriggers(TestGlobals.PROJECT, TestGlobals.COLL);
        cache.removeTriggers(TestGlobals.PROJECT, TestGlobals.COLL);
        for (final var name : fs.metadataFiles().listProcedureNames(TestGlobals.PROJECT)) {
            fs.metadataFiles().deleteProcedure(TestGlobals.PROJECT, name);
        }
        cache.removeProceduresForProject(TestGlobals.PROJECT);
        ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.PROJECT, "recalc", "return 1;"),
                ACTOR);
    }

    private static SaveTriggerRequest before() {
        final var request = new SaveTriggerRequest(TestGlobals.PROJECT, TestGlobals.COLL, "validate",
                List.of("CREATED"), "recalc");
        request.setTiming(TriggerDefinition.TIMING_BEFORE);
        return request;
    }

    @Test
    public void test_saves_a_before_trigger() throws Exception {
        final var response = TriggerOperationHelper.executeSave(before(), ACTOR);
        assertInstanceOf(SaveTriggerResponse.class, response, response.getMessage());
        final var stored = cache.getTriggersFor(TestGlobals.PROJECT, TestGlobals.COLL);
        assertEquals(1, stored.size());
        assertTrue(stored.getFirst().isBefore());
    }

    @Test
    public void test_defaults_to_after_when_timing_omitted() throws Exception {
        final var request = new SaveTriggerRequest(TestGlobals.PROJECT, TestGlobals.COLL, "audit", List.of("CREATED"),
                "recalc");
        assertInstanceOf(SaveTriggerResponse.class, TriggerOperationHelper.executeSave(request, ACTOR));
        assertFalse(cache.getTriggersFor(TestGlobals.PROJECT, TestGlobals.COLL).getFirst().isBefore());
    }

    @Test
    public void test_rejects_unknown_timing() throws Exception {
        final var request = before();
        request.setTiming("sideways");
        final var response = TriggerOperationHelper.executeSave(request, ACTOR);
        assertEquals(ErrorCode.INVALID_TRIGGER.getCode(), response.getErrorCode());
        assertTrue(response.getMessage().contains("timing"));
    }

    @Test
    public void test_rejects_batch_mode_on_a_before_trigger() throws Exception {
        final var request = before();
        request.setMode(TriggerDefinition.MODE_BATCH);
        final var response = TriggerOperationHelper.executeSave(request, ACTOR);
        assertEquals(ErrorCode.INVALID_TRIGGER.getCode(), response.getErrorCode());
        assertTrue(response.getMessage().contains("batch"));
    }

    @Test
    public void test_rejects_allow_cascade_on_a_before_trigger() throws Exception {
        final var request = before();
        request.setAllowCascade(true);
        final var response = TriggerOperationHelper.executeSave(request, ACTOR);
        assertEquals(ErrorCode.INVALID_TRIGGER.getCode(), response.getErrorCode());
        assertTrue(response.getMessage().contains("cascade"));
    }

    @Test
    public void test_allows_batch_mode_on_an_after_trigger() throws Exception {
        final var request = new SaveTriggerRequest(TestGlobals.PROJECT, TestGlobals.COLL, "audit", List.of("CREATED"),
                "recalc");
        request.setMode(TriggerDefinition.MODE_BATCH);
        assertInstanceOf(SaveTriggerResponse.class, TriggerOperationHelper.executeSave(request, ACTOR));
    }

    @Test
    public void test_rejects_a_before_trigger_naming_a_missing_procedure() throws Exception {
        final var request = new SaveTriggerRequest(TestGlobals.PROJECT, TestGlobals.COLL, "validate",
                List.of("CREATED"), "nonexistent");
        request.setTiming(TriggerDefinition.TIMING_BEFORE);
        assertEquals(ErrorCode.PROCEDURE_NOT_FOUND.getCode(),
                TriggerOperationHelper.executeSave(request, ACTOR).getErrorCode());
    }
}
