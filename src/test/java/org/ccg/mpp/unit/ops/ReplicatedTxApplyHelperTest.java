package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.ccg.mpp.cluster.msg.ReplicationOp;
import org.ccg.mpp.cluster.msg.ReplicationPayload;
import org.ccg.mpp.cluster.msg.TxReplicationPayload;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.ReplicatedApplyHelper;
import org.ccg.mpp.ops.ReplicatedTxApplyHelper;
import org.ccg.mpp.ops.admin.CollectionIncarnation;
import org.ccg.mpp.ops.req.CreateCollectionRequest;
import org.ccg.mpp.ops.req.FindByIdRequest;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class ReplicatedTxApplyHelperTest {
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static JsonObject doc(String id) {
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        return object;
    }

    private OperationStatus findStatus(String id) {
        final var request = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id(id);
        return processor.processMessage(request).getStatus();
    }

    @Test
    public void test_null_or_empty_batch_returns_false() {
        assertFalse(ReplicatedTxApplyHelper.apply(null, TestGlobals.APPLY_BUDGET_MS));
        assertFalse(ReplicatedTxApplyHelper.apply(new TxReplicationPayload(), TestGlobals.APPLY_BUDGET_MS));
    }

    @Test
    public void test_applies_upsert_and_delete_entries_atomically() throws Exception {
        ReplicatedApplyHelper.apply(new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL, ReplicationOp.UPSERT,
                List.of(doc("tx-del")), null, List.of("1")), TestGlobals.APPLY_BUDGET_MS);
        assertEquals(OperationStatus.OK, findStatus("tx-del"));

        final var upsert = new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL, ReplicationOp.UPSERT,
                List.of(doc("tx-a"), doc("tx-b")), null, List.of("10", "11"));
        final var delete = new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL, ReplicationOp.DELETE, null,
                List.of("tx-del"), List.of("12"));

        assertTrue(ReplicatedTxApplyHelper.apply(new TxReplicationPayload(List.of(upsert, delete)),
                TestGlobals.APPLY_BUDGET_MS));

        assertEquals(OperationStatus.OK, findStatus("tx-a"));
        assertEquals(OperationStatus.OK, findStatus("tx-b"));
        assertEquals(OperationStatus.NOT_FOUND, findStatus("tx-del"));
        assertEquals(12L, fs.tombstones().read(TestGlobals.DB, TestGlobals.COLL).get("tx-del"));
    }

    @Test
    public void test_a_batch_with_one_stale_entry_applies_nothing() {
        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, "txIncarnated")).getStatus());
        final var current = new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL, ReplicationOp.UPSERT,
                List.of(doc("tx-current")), null, List.of("20"));
        current.setIncarnationValue(CollectionIncarnation.current(TestGlobals.DB, TestGlobals.COLL));
        final var stale = new ReplicationPayload(TestGlobals.DB, "txIncarnated", ReplicationOp.UPSERT,
                List.of(doc("tx-stale")), null, List.of("21"));
        stale.setIncarnationValue(CollectionIncarnation.current(TestGlobals.DB, "txIncarnated") + 1);

        assertFalse(ReplicatedTxApplyHelper.apply(new TxReplicationPayload(List.of(current, stale)),
                TestGlobals.APPLY_BUDGET_MS));

        assertEquals(OperationStatus.NOT_FOUND, findStatus("tx-current"),
                "a replica must not keep half of a transaction batch whose other half is refused");
    }
}
