package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.req.DeleteTriggerRequest;
import org.ccg.mpp.ops.req.ListTriggersRequest;
import org.ccg.mpp.ops.req.SaveProcedureRequest;
import org.ccg.mpp.ops.req.SaveTriggerRequest;
import org.ccg.mpp.ops.resp.ListTriggersResponse;
import org.ccg.mpp.ops.resp.SaveTriggerResponse;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class OperationProcessorTriggerDispatchTest {
    private static final Configuration configuration = Configuration.getInstance();
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
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
        cache.removeTriggers(TestGlobals.PROJECT, TestGlobals.COLL);
        fs.metadataFiles().deleteTriggers(TestGlobals.PROJECT, TestGlobals.COLL);
        for (final var name : fs.metadataFiles().listProcedureNames(TestGlobals.PROJECT)) {
            fs.metadataFiles().deleteProcedure(TestGlobals.PROJECT, name);
        }
        cache.removeProceduresForProject(TestGlobals.PROJECT);
        processor.processMessage(new SaveProcedureRequest(TestGlobals.PROJECT, "audit", "export default () => 1;"));
    }

    private SaveTriggerRequest saveRequest() {
        return new SaveTriggerRequest(TestGlobals.PROJECT, TestGlobals.COLL, "onInsert", List.of("CREATED"), "audit");
    }

    @Test
    public void test_dispatches_save_list_and_delete() {
        final var saved = processor.processMessage(saveRequest());
        assertInstanceOf(SaveTriggerResponse.class, saved, saved.getMessage());

        final var listed = (ListTriggersResponse) processor
                .processMessage(new ListTriggersRequest(TestGlobals.PROJECT, TestGlobals.COLL));
        assertEquals(1, listed.getTriggers().size());
        assertEquals("onInsert", listed.getTriggers().getFirst().get("name").asJsonString().getValue());

        assertEquals(OperationStatus.OK,
                processor.processMessage(new DeleteTriggerRequest(TestGlobals.PROJECT, TestGlobals.COLL, "onInsert"))
                        .getStatus());
        final var afterDelete = (ListTriggersResponse) processor
                .processMessage(new ListTriggersRequest(TestGlobals.PROJECT, TestGlobals.COLL));
        assertTrue(afterDelete.getTriggers().isEmpty());
    }

    @Test
    public void test_listing_the_whole_project_tags_each_trigger_with_its_collection() {
        processor.processMessage(saveRequest());
        final var listed = (ListTriggersResponse) processor
                .processMessage(new ListTriggersRequest(TestGlobals.PROJECT, null));
        assertEquals(1, listed.getTriggers().size());
        assertEquals(TestGlobals.COLL, listed.getTriggers().getFirst().get("collectionName").asJsonString().getValue());
    }

    @Test
    public void test_saving_a_trigger_on_an_unknown_collection_is_refused() {
        final var request = new SaveTriggerRequest(TestGlobals.PROJECT, "noSuchColl", "onInsert", List.of("CREATED"),
                "audit");
        final var response = processor.processMessage(request);
        assertEquals(ErrorCode.PROJECT_NOT_FOUND.getCode(), response.getErrorCode());
    }

    @Test
    public void test_deleting_a_trigger_on_an_unknown_collection_is_refused() {
        final var response = processor
                .processMessage(new DeleteTriggerRequest(TestGlobals.PROJECT, "noSuchColl", "onInsert"));
        assertEquals(ErrorCode.PROJECT_NOT_FOUND.getCode(), response.getErrorCode());
    }

    @Test
    public void test_saving_a_trigger_for_an_unknown_procedure_is_refused() {
        final var request = new SaveTriggerRequest(TestGlobals.PROJECT, TestGlobals.COLL, "onInsert",
                List.of("CREATED"), "missing");
        final var response = processor.processMessage(request);
        assertEquals(ErrorCode.PROCEDURE_NOT_FOUND.getCode(), response.getErrorCode());
    }

    @Test
    public void test_deleting_a_trigger_that_is_not_there_is_idempotent() {
        assertEquals(OperationStatus.OK, processor
                .processMessage(new DeleteTriggerRequest(TestGlobals.PROJECT, TestGlobals.COLL, "absent")).getStatus());
    }
}
