package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.ccg.mpp.bckg_ops.events.EventType;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.ScriptRunRegistry;
import org.ccg.mpp.ops.req.BulkSaveRequest;
import org.ccg.mpp.ops.req.CommitTransactionRequest;
import org.ccg.mpp.ops.req.FindByIdRequest;
import org.ccg.mpp.ops.req.RollbackTransactionRequest;
import org.ccg.mpp.ops.req.StartTransactionRequest;
import org.ccg.mpp.ops.resp.FindByIdResponse;
import org.ccg.mpp.ops.resp.OperationResponse;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TransactionBulkBeforeHookTest {
    private static final String COUNTING_HOOK = "let calls = 0; export default (d) => ({ ...d, call: ++calls });";
    private static final String LOOPING_HOOK = "export default (d) => { let s = 0;"
            + " for (let i = 0; i < 1000; i++) { s += i; } return { ...d, s }; };";
    private static final int OVER_BUDGET_BULK_SIZE = 100;
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final ScriptRunRegistry runRegistry = IocContainer.get(ScriptRunRegistry.class);

    @BeforeAll
    static void setUp() throws Exception {
        BeforeHookTestSupport.setUpAll();
    }

    @AfterAll
    static void tearDown() throws Exception {
        BeforeHookTestSupport.tearDownAll();
    }

    @BeforeEach
    void reset() throws Exception {
        BeforeHookTestSupport.reset();
    }

    private static BulkSaveRequest bulkOf(String... ids) {
        final var objects = new ArrayList<JsonObject>();
        for (final var id : ids) {
            objects.add(BeforeHookTestSupport.document(id));
        }
        final var request = new BulkSaveRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        request.setObjects(objects);
        return request;
    }

    private OperationResponse bulkInTransaction(String... ids) {
        final var client = BeforeHookTestSupport.newClient();
        processor.processMessage(new StartTransactionRequest(), client);
        final var response = processor.processMessage(bulkOf(ids), client);
        commitOrRollBack(client, response);
        return response;
    }

    private void commitOrRollBack(UUID client, OperationResponse response) {
        if (response.getStatus() == OperationStatus.OK) {
            assertEquals(OperationStatus.OK,
                    processor.processMessage(new CommitTransactionRequest(), client).getStatus());
        } else {
            processor.processMessage(new RollbackTransactionRequest(), client);
        }
    }

    private int callOf(String id) {
        final var request = new FindByIdRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        request.set_id(id);
        final var found = (FindByIdResponse) processor.processMessage(request);
        assertNotNull(found.getObject());
        return found.getObject().get("call").asJsonNumber().getValue().intValue();
    }

    private static String[] overBudgetIds(String prefix) {
        final var ids = new String[OVER_BUDGET_BULK_SIZE];
        for (var i = 0; i < ids.length; i++) {
            ids[i] = prefix + i;
        }
        return ids;
    }

    @Test
    public void test_a_transactional_bulk_save_shares_one_module_instance() throws Exception {
        BeforeHookTestSupport.installHook("counter", "counter", COUNTING_HOOK, EventType.CREATED, EventType.UPDATED);

        assertEquals(OperationStatus.OK, bulkInTransaction("m1", "m2", "m3").getStatus());

        assertEquals(List.of(1, 2, 3), List.of(callOf("m1"), callOf("m2"), callOf("m3")));
    }

    @Test
    public void test_a_transactional_bulk_save_numbers_like_a_standalone_one() throws Exception {
        BeforeHookTestSupport.installHook("counter", "counter", COUNTING_HOOK, EventType.CREATED, EventType.UPDATED);

        assertEquals(OperationStatus.OK, processor.processMessage(bulkOf("s1", "s2", "s3")).getStatus());
        assertEquals(OperationStatus.OK, bulkInTransaction("x1", "x2", "x3").getStatus());

        assertEquals(List.of(callOf("s1"), callOf("s2"), callOf("s3")),
                List.of(callOf("x1"), callOf("x2"), callOf("x3")));
    }

    @Test
    public void test_creates_and_updates_keep_separate_contexts_in_a_transaction() throws Exception {
        assertEquals(OperationStatus.OK, processor.processMessage(bulkOf("e1")).getStatus());
        BeforeHookTestSupport.installHook("counter", "counter", COUNTING_HOOK, EventType.CREATED, EventType.UPDATED);

        assertEquals(OperationStatus.OK, bulkInTransaction("n1", "e1", "n2").getStatus());

        assertEquals(List.of(1, 1, 2), List.of(callOf("n1"), callOf("e1"), callOf("n2")));
    }

    @Test
    public void test_a_transactional_bulk_save_shares_one_budget() throws Exception {
        BeforeHookTestSupport.installHook("looping", "looping", LOOPING_HOOK, EventType.CREATED, EventType.UPDATED);
        TestUtils.setPrivateField(Configuration.getInstance(), "beforeHookInstructionBudget", 30_000L);

        assertEquals(OperationStatus.OK, bulkInTransaction("b0").getStatus());
        final var standalone = processor.processMessage(bulkOf(overBudgetIds("bs")));
        final var transactional = bulkInTransaction(overBudgetIds("bt"));

        assertNotEquals(OperationStatus.OK, standalone.getStatus());
        assertEquals(standalone.getErrorCode(), transactional.getErrorCode());
    }

    @Test
    public void test_a_rejected_transactional_bulk_save_closes_its_contexts() throws Exception {
        BeforeHookTestSupport.installHook("veto", "veto",
                "export default (d) => { if (d._id === 'r2') { throw new Error('no'); } };", EventType.CREATED,
                EventType.UPDATED);
        final var before = runRegistry.size();

        assertEquals(ErrorCode.BEFORE_HOOK_REJECTED.getCode(), bulkInTransaction("r1", "r2").getErrorCode());

        assertEquals(before, runRegistry.size());
    }
}
