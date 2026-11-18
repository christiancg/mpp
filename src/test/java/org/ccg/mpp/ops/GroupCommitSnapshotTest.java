package org.ccg.mpp.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.fault.FaultAction;
import org.ccg.mpp.fault.FaultInjector;
import org.ccg.mpp.fault.FaultPoint;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.fs.RecordingForcer;
import org.ccg.mpp.fs.RetiredCopies;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.req.ReadSnapshotRequest;
import org.ccg.mpp.ops.req.StartSnapshotRequest;
import org.ccg.mpp.ops.resp.OperationResponse;
import org.ccg.mpp.ops.resp.ReadSnapshotResponse;
import org.ccg.mpp.ops.resp.StartSnapshotResponse;
import org.ccg.mpp.ops.snapshot.SnapshotOperationHelper;
import org.ccg.mpp.ops.snapshot.SnapshotRegistry;
import org.ccg.mpp.test.RequiresDirectorySync;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@RequiresDirectorySync
public class GroupCommitSnapshotTest {
    private static final long WAIT_MS = GroupCommitFixture.WAIT_MS;
    private static final long BLOCKED_MS = 300L;
    private static final String SUBJECT = Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL);
    private static final String USER = "group-snapper";
    private final FaultInjector faultInjector = IocContainer.get(FaultInjector.class);
    private final SnapshotRegistry registry = IocContainer.get(SnapshotRegistry.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final UUID client = UUID.randomUUID();
    private RecordingForcer recorder;
    private GroupCommitFixture fixture;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.ensureAdminUser(USER);
        recorder = RecordingForcer.install(true);
        fixture = new GroupCommitFixture(recorder);
        assertEquals(OperationStatus.OK, fixture.save("seed", "v0").getStatus());
        fs.snapshotFiles().onRetiredCopies(registry);
        TestUtils.setPrivateField(faultInjector, "enabled", true);
    }

    @AfterEach
    public void tearDown() throws Exception {
        faultInjector.releaseAll();
        TestUtils.setPrivateField(faultInjector, "enabled", false);
        registry.releaseAll();
        fs.snapshotFiles().onRetiredCopies(RetiredCopies.NONE);
        recorder.close();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static <T> CompletableFuture<T> inBackground(Supplier<T> work) {
        return CompletableFuture.supplyAsync(work, command -> Thread.ofVirtual().start(command));
    }

    private OperationResponse readSnapshot(String snapshotId) {
        final var request = new ReadSnapshotRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setSnapshotId(snapshotId);
        request.setLimit(100);
        return SnapshotOperationHelper.read(request, client);
    }

    @Test
    public void test_a_snapshot_page_waits_for_the_group_force_and_reads_the_images_its_plans_kept() throws Exception {
        final var snapshotId = assertInstanceOf(StartSnapshotResponse.class,
                SnapshotOperationHelper.start(new StartSnapshotRequest(TestGlobals.DB), client, USER)).getSnapshotId();
        faultInjector.arm(FaultPoint.WAL_GROUP_APPENDED, FaultAction.PAUSE, SUBJECT);
        final var writes = new ArrayList<Supplier<OperationResponse>>();
        writes.add(() -> fixture.save("seed", "grouped"));
        writes.addAll(GroupCommitFixture.times(3, number -> fixture.save("w" + number, "v" + number)));
        final var group = fixture.runQueuedInBackground(writes);
        assertTrue(faultInjector.awaitReached(FaultPoint.WAL_GROUP_APPENDED, WAIT_MS), "the group never forced");

        final var page = inBackground(() -> readSnapshot(snapshotId));

        assertThrows(TimeoutException.class, () -> page.get(BLOCKED_MS, TimeUnit.MILLISECONDS));
        faultInjector.release(FaultPoint.WAL_GROUP_APPENDED);
        group.get(WAIT_MS, TimeUnit.MILLISECONDS);
        final var results = assertInstanceOf(ReadSnapshotResponse.class, page.get(WAIT_MS, TimeUnit.MILLISECONDS))
                .getResults();
        assertEquals(List.of("seed=v0"),
                results.stream().map(document -> document.get(Globals.PK_FIELD).asJsonString().getValue() + "="
                        + document.get("v").asJsonString().getValue()).toList());
    }
}
