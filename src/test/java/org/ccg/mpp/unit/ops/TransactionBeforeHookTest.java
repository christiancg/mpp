package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.ccg.mpp.bckg_ops.events.EventType;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.ejson.elements.JsonNumber;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.req.BulkSaveRequest;
import org.ccg.mpp.ops.req.CommitTransactionRequest;
import org.ccg.mpp.ops.req.DeleteRequest;
import org.ccg.mpp.ops.req.FindByIdRequest;
import org.ccg.mpp.ops.req.RollbackTransactionRequest;
import org.ccg.mpp.ops.req.SaveRequest;
import org.ccg.mpp.ops.req.StartTransactionRequest;
import org.ccg.mpp.ops.resp.BulkSaveResponse;
import org.ccg.mpp.ops.resp.FindByIdResponse;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TransactionBeforeHookTest {
    private static final Configuration configuration = Configuration.getInstance();
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

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

    private void installHook(String name, String procedure, String source, EventType... events) throws Exception {
        BeforeHookTestSupport.installHook(name, procedure, source, events);
    }

    private UUID newClient() {
        return BeforeHookTestSupport.newClient();
    }

    private static JsonObject document(String id) {
        return BeforeHookTestSupport.document(id);
    }

    private org.ccg.mpp.ops.resp.OperationResponse save(String id, UUID clientId) {
        final var request = new SaveRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        request.setObject(document(id));
        request.set_id(id);
        return processor.processMessage(request, clientId);
    }

    private JsonObject find(String id) {
        final var request = new FindByIdRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        request.set_id(id);
        final var response = processor.processMessage(request);
        return response instanceof FindByIdResponse found ? found.getObject() : null;
    }

    @Test
    public void test_buffer_save_applies_the_replacement() throws Exception {
        installHook("calc", "calc", "export default (d) => ({ ...d, total: d.qty * d.price });", EventType.CREATED,
                EventType.UPDATED);
        final var client = newClient();
        processor.processMessage(new StartTransactionRequest(), client);
        assertEquals(OperationStatus.OK, save("t1", client).getStatus());
        assertEquals(OperationStatus.OK, processor.processMessage(new CommitTransactionRequest(), client).getStatus());
        assertEquals(20.0, Objects.requireNonNull(find("t1")).get("total").asJsonNumber().getValue().doubleValue());
    }

    @Test
    public void test_buffer_save_rejection_buffers_nothing() throws Exception {
        installHook("veto", "veto", "export default (d) => { throw new Error('nope'); };", EventType.CREATED,
                EventType.UPDATED);
        final var client = newClient();
        processor.processMessage(new StartTransactionRequest(), client);
        assertEquals(ErrorCode.BEFORE_HOOK_REJECTED.getCode(), save("t2", client).getErrorCode());
        processor.processMessage(new CommitTransactionRequest(), client);
        assertNull(find("t2"));
    }

    // The ordering that matters: a hook can inflate a document past maxEntrySize, so the replacement has
    // to be size-checked rather than only the caller's own document.
    @Test
    public void test_a_replacement_is_size_checked() throws Exception {
        installHook("fat", "fat", "export default (d) => ({ ...d, blob: 'x'.repeat(3000) });", EventType.CREATED,
                EventType.UPDATED);
        TestUtils.setPrivateField(configuration, "maxEntrySize", 1_000L);
        try {
            final var client = newClient();
            processor.processMessage(new StartTransactionRequest(), client);
            assertEquals(ErrorCode.ENTRY_TOO_LARGE.getCode(), save("t3", client).getErrorCode());
        } finally {
            TestUtils.setPrivateField(configuration, "maxEntrySize", 1_048_576L);
        }
    }

    @Test
    public void test_buffer_bulk_save_applies_the_replacement() throws Exception {
        installHook("calc", "calc", "export default (d) => ({ ...d, total: d.qty * d.price });", EventType.CREATED,
                EventType.UPDATED);
        final var client = newClient();
        processor.processMessage(new StartTransactionRequest(), client);
        final var request = new BulkSaveRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        request.setObjects(List.of(document("t4"), document("t5")));
        assertEquals(OperationStatus.OK, processor.processMessage(request, client).getStatus());
        assertEquals(OperationStatus.OK, processor.processMessage(new CommitTransactionRequest(), client).getStatus());
        assertEquals(20.0, Objects.requireNonNull(find("t4")).get("total").asJsonNumber().getValue().doubleValue());
        assertEquals(20.0, Objects.requireNonNull(find("t5")).get("total").asJsonNumber().getValue().doubleValue());
    }

    @Test
    public void test_buffer_bulk_save_rejection_buffers_nothing() throws Exception {
        installHook("veto", "veto", "export default (d) => { if (d._id === 't7') { throw new Error('no'); } };",
                EventType.CREATED, EventType.UPDATED);
        final var client = newClient();
        processor.processMessage(new StartTransactionRequest(), client);
        final var request = new BulkSaveRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        request.setObjects(List.of(document("t6"), document("t7")));
        assertEquals(ErrorCode.BEFORE_HOOK_REJECTED.getCode(),
                processor.processMessage(request, client).getErrorCode());
        processor.processMessage(new CommitTransactionRequest(), client);
        assertNull(find("t6"));
        assertNull(find("t7"));
    }

    @Test
    public void test_buffer_delete_veto_leaves_the_document() throws Exception {
        assertEquals(OperationStatus.OK, save("t8", newClient()).getStatus());
        installHook("lock", "lock", "export default (d) => { throw new Error('locked'); };", EventType.DELETED);
        final var client = newClient();
        processor.processMessage(new StartTransactionRequest(), client);
        final var request = new DeleteRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        request.set_id("t8");
        assertEquals(ErrorCode.BEFORE_HOOK_REJECTED.getCode(),
                processor.processMessage(request, client).getErrorCode());
        processor.processMessage(new CommitTransactionRequest(), client);
        assertNotNull(find("t8"));
    }

    @Test
    public void test_buffer_delete_proceeds_when_the_hook_accepts() throws Exception {
        assertEquals(OperationStatus.OK, save("t9", newClient()).getStatus());
        installHook("ok", "okhook", "export default (d) => { };", EventType.DELETED);
        final var client = newClient();
        processor.processMessage(new StartTransactionRequest(), client);
        final var request = new DeleteRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        request.set_id("t9");
        assertEquals(OperationStatus.OK, processor.processMessage(request, client).getStatus());
        assertEquals(OperationStatus.OK, processor.processMessage(new CommitTransactionRequest(), client).getStatus());
        assertNull(find("t9"));
    }

    private void vetoOn(String id) throws Exception {
        installHook("veto", "veto", "export default (d) => { if (d._id === '" + id + "') { throw new Error('no'); } };",
                EventType.CREATED, EventType.UPDATED);
    }

    private void bulkRejectedAt(UUID client, String keptId, String vetoedId) {
        final var request = new BulkSaveRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        request.setObjects(List.of(document(keptId), document(vetoedId)));
        assertEquals(ErrorCode.BEFORE_HOOK_REJECTED.getCode(),
                processor.processMessage(request, client).getErrorCode());
    }

    @Test
    public void test_a_rejected_bulk_save_leaves_no_overlay_entries() throws Exception {
        vetoOn("t20");
        final var client = newClient();
        processor.processMessage(new StartTransactionRequest(), client);
        try {
            bulkRejectedAt(client, "t19", "t20");

            final var request = new FindByIdRequest(TestGlobals.PROJECT, TestGlobals.COLL);
            request.set_id("t19");
            assertEquals(OperationStatus.NOT_FOUND, processor.processMessage(request, client).getStatus(),
                    "an object from a refused bulk save must not be readable through the transaction's overlay");
        } finally {
            processor.processMessage(new RollbackTransactionRequest(), client);
        }
    }

    @Test
    public void test_a_later_save_of_a_rejected_id_is_classified_as_an_insert() throws Exception {
        vetoOn("t22");
        final var client = newClient();
        processor.processMessage(new StartTransactionRequest(), client);
        try {
            bulkRejectedAt(client, "t21", "t22");

            final var retry = new BulkSaveRequest(TestGlobals.PROJECT, TestGlobals.COLL);
            retry.setObjects(List.of(document("t21")));
            final var response = (BulkSaveResponse) processor.processMessage(retry, client);

            assertEquals(List.of("t21"), response.getInserted(),
                    "a phantom overlay entry would classify the retry as an update and fire the wrong event");
            assertTrue(response.getUpdated().isEmpty());
        } finally {
            processor.processMessage(new RollbackTransactionRequest(), client);
        }
    }

    @Test
    public void test_an_idless_transactional_save_whose_hook_drops_the_id_is_refused() throws Exception {
        installHook("freshTx", "freshTx", "export default () => ({ fresh: true });", EventType.CREATED);
        final var client = newClient();
        processor.processMessage(new StartTransactionRequest(), client);
        final var object = new JsonObject();
        object.add("qty", new JsonNumber(1));
        final var request = new SaveRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        request.setObject(object);

        assertEquals(ErrorCode.BEFORE_HOOK_REJECTED.getCode(),
                processor.processMessage(request, client).getErrorCode());
        processor.processMessage(new org.ccg.mpp.ops.req.RollbackTransactionRequest(), client);
    }
}
