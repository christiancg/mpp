package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.ccg.mpp.cluster.HybridClock;
import org.ccg.mpp.cluster.msg.ReplicationOp;
import org.ccg.mpp.cluster.msg.ReplicationPayload;
import org.ccg.mpp.cluster.msg.TxReplicationPayload;
import org.ccg.mpp.config.ConfigKey;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.ReplicatedApplyHelper;
import org.ccg.mpp.ops.ReplicatedTxApplyHelper;
import org.ccg.mpp.ops.req.FindByIdRequest;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ReplicatedApplyHelperPeerInputTest {
    private static final long SKEW_MS = 60_000L;
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final HybridClock clock = IocContainer.get(HybridClock.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final Configuration config = Configuration.getInstance();
    private long origSkew;
    private long origClock;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
        origSkew = config.longValue(ConfigKey.CLUSTER_MAX_CLOCK_SKEW_MS);
        TestUtils.setPrivateField(config, "clusterMaxClockSkewMs", SKEW_MS);
        origClock = clock.current();
        TestUtils.getPrivateField(clock, "last", AtomicLong.class).set(0L);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(config, "clusterMaxClockSkewMs", origSkew);
        clock.observe(origClock);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static JsonObject doc(String id) {
        final var object = new JsonObject();
        object.addProperty("_id", id);
        return object;
    }

    private static ReplicationPayload upsert(String id, long version) {
        return new ReplicationPayload(TestGlobals.PROJECT, TestGlobals.COLL, ReplicationOp.UPSERT, List.of(doc(id)),
                null, List.of(Long.toString(version)));
    }

    private OperationStatus findStatus(String id) {
        final var request = new FindByIdRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        request.set_id(id);
        return processor.processMessage(request).getStatus();
    }

    private static long ahead(long millis) {
        return HybridClock.pack(System.currentTimeMillis() + millis, 0);
    }

    @Test
    public void aPayloadForATraversalCollectionIsRefused() {
        final var payload = new ReplicationPayload("../peer_escape_marker", TestGlobals.COLL, ReplicationOp.UPSERT,
                List.of(doc("t1")), null);
        assertFalse(ReplicatedApplyHelper.apply(payload, TestGlobals.APPLY_BUDGET_MS));
        assertFalse(Files.exists(Path.of(TestGlobals.PATH).toAbsolutePath().getParent().resolve("peer_escape_marker")));
        assertFalse(ReplicatedApplyHelper.apply(
                new ReplicationPayload("admin", "users", ReplicationOp.DELETE, null, List.of("admin")),
                TestGlobals.APPLY_BUDGET_MS));
    }

    @Test
    public void aTransactionTouchingAReservedProjectIsRefused() {
        final var payload = new TxReplicationPayload(List.of(upsert("ok1", clock.next()),
                new ReplicationPayload("cluster", "nodes", ReplicationOp.UPSERT, List.of(doc("n1")), null)));
        assertFalse(ReplicatedTxApplyHelper.apply(payload, TestGlobals.APPLY_BUDGET_MS));
        assertEquals(OperationStatus.NOT_FOUND, findStatus("ok1"));
    }

    @Test
    public void aVersionBeyondTheSkewIsNotAppliedNorObserved() {
        final var version = ahead(SKEW_MS * 10);
        assertFalse(ReplicatedApplyHelper.apply(upsert("future", version), TestGlobals.APPLY_BUDGET_MS));
        assertEquals(OperationStatus.NOT_FOUND, findStatus("future"));
        assertTrue(clock.current() < version);
    }

    @Test
    public void aVersionJustInsideTheSkewIsApplied() {
        final var version = ahead(SKEW_MS / 2);
        assertTrue(ReplicatedApplyHelper.apply(upsert("near", version), TestGlobals.APPLY_BUDGET_MS));
        assertEquals(OperationStatus.OK, findStatus("near"));
        assertTrue(clock.current() >= version);
    }

    @Test
    public void aDeleteBeyondTheSkewAppendsNoTombstone() throws Exception {
        final var version = ahead(SKEW_MS * 10);
        final var payload = new ReplicationPayload(TestGlobals.PROJECT, TestGlobals.COLL, ReplicationOp.DELETE, null,
                List.of("gone"), List.of(Long.toString(version)));
        assertFalse(ReplicatedApplyHelper.apply(payload, TestGlobals.APPLY_BUDGET_MS));
        assertFalse(fs.tombstones().read(TestGlobals.PROJECT, TestGlobals.COLL).containsKey("gone"));
    }

    @Test
    public void aTransactionWithOneVersionBeyondTheSkewAppliesNothing() {
        final var payload = new TxReplicationPayload(
                List.of(upsert("first", clock.next()), upsert("second", ahead(SKEW_MS * 10))));
        assertFalse(ReplicatedTxApplyHelper.apply(payload, TestGlobals.APPLY_BUDGET_MS));
        assertEquals(OperationStatus.NOT_FOUND, findStatus("first"));
        assertEquals(OperationStatus.NOT_FOUND, findStatus("second"));
    }

    @Test
    public void clockNextStillIncreasesAfterARefusedHugeVersion() {
        final var before = clock.next();
        assertFalse(ReplicatedApplyHelper.apply(upsert("huge", Long.MAX_VALUE), TestGlobals.APPLY_BUDGET_MS));
        final var after = clock.next();
        assertTrue(after > before);
        assertTrue(HybridClock.physicalOf(after) <= System.currentTimeMillis() + SKEW_MS);
    }
}
