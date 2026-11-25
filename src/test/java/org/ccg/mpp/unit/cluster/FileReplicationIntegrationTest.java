package org.ccg.mpp.unit.cluster;

import static org.ccg.mpp.test.ClusterTestHarness.SECRET;
import static org.ccg.mpp.test.ClusterTestHarness.node;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.ccg.mpp.cluster.ClusterCoordinator;
import org.ccg.mpp.cluster.PeerConnectionPool;
import org.ccg.mpp.cluster.ReplicationOutcome;
import org.ccg.mpp.cluster.files.FilePayloads;
import org.ccg.mpp.cluster.files.FileReplicaApplier;
import org.ccg.mpp.cluster.msg.ClusterMessage;
import org.ccg.mpp.cluster.msg.ClusterMessageType;
import org.ccg.mpp.cluster.msg.FilePayload;
import org.ccg.mpp.cluster.msg.FileReplicationOp;
import org.ccg.mpp.cluster.ownership.OwnershipManager;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.FileWriteMode;
import org.ccg.mpp.data.ProjectFilePath;
import org.ccg.mpp.fs.ManifestLockHolder;
import org.ccg.mpp.fs.ProjectFileStore;
import org.ccg.mpp.fs.ProjectFileWrites;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.test.ClusterTestHarness;
import org.ccg.mpp.test.ProjectFilesFixture;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class FileReplicationIntegrationTest {
    private static final String PROJECT = TestGlobals.PROJECT;
    private final ClusterTestHarness cluster = new ClusterTestHarness();
    private final PeerConnectionPool pool = IocContainer.get(PeerConnectionPool.class);
    private final ClusterCoordinator coordinator = IocContainer.get(ClusterCoordinator.class);
    private final OwnershipManager ownership = IocContainer.get(OwnershipManager.class);
    private ProjectFileStore store;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
        store = ProjectFilesFixture.start();
        cluster.start();
    }

    @AfterEach
    public void tearDown() throws Exception {
        cluster.stop();
        ProjectFilesFixture.stop();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static FileReplicationOp op(String type, String path, long version, String content) {
        final var op = new FileReplicationOp();
        op.setType(type);
        op.setPath(path);
        op.setVersion(Long.toString(version));
        if (content != null) {
            op.setContent(Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
            op.setSize(content.length());
        }
        return op;
    }

    private ClusterMessage send(ClusterMessageType type, FilePayload payload) throws Exception {
        final var message = new ClusterMessage(null, type, SECRET, node("peer", 1), null);
        message.setFiles(payload);
        return pool.request(cluster.serverAddress(), message, 3000);
    }

    private static FilePayload payload(FileReplicationOp... ops) {
        final var payload = FilePayloads.header(PROJECT, 0L);
        payload.setOps(List.of(ops));
        return payload;
    }

    @Test
    public void test_replicated_files_apply_over_the_peer_channel() throws Exception {
        final var version = ProjectFilesFixture.nextVersion();
        final var ack = send(ClusterMessageType.REPLICATE_FILES,
                payload(op("DIRECTORY", "/r", version, null), op("FILE", "/r/f", version, "replicated")));
        assertEquals(ClusterMessageType.REPLICATE_FILES_ACK, ack.getType());
        assertEquals(10, store.stat(PROJECT, ProjectFilePath.parse("/r/f")).size());
        final var refused = send(ClusterMessageType.REPLICATE_FILES, payload(op("FILE", "/missing/f", version, "x")));
        assertEquals(ClusterMessageType.ERROR, refused.getType());
    }

    @Test
    public void test_a_replica_whose_files_stay_locked_answers_an_error_instead_of_parking() throws Exception {
        final var version = ProjectFilesFixture.nextVersion();
        try (var _ = ManifestLockHolder.hold(store, PROJECT)) {
            final var refused = send(ClusterMessageType.REPLICATE_FILES, payload(op("FILE", "/held", version, "h")));
            assertEquals(ClusterMessageType.ERROR, refused.getType());
            final var digest = send(ClusterMessageType.FILE_DIGEST, FilePayloads.header(PROJECT, 0L));
            assertEquals(ClusterMessageType.ERROR, digest.getType());
        }
        final var applied = send(ClusterMessageType.REPLICATE_FILES, payload(op("FILE", "/held", version, "h")));
        assertEquals(ClusterMessageType.REPLICATE_FILES_ACK, applied.getType());
    }

    @Test
    public void test_digest_and_fetch_describe_the_local_files() throws Exception {
        final var version = ProjectFilesFixture.nextVersion();
        store.replica(PROJECT).applyFile("/served.txt", version, "served".getBytes(StandardCharsets.UTF_8));
        final var digest = send(ClusterMessageType.FILE_DIGEST, FilePayloads.header(PROJECT, 0L));
        assertEquals(ClusterMessageType.FILE_DIGEST_ACK, digest.getType());
        assertEquals(1, digest.getFiles().getOps().size());
        final var query = FilePayloads.header(PROJECT, 0L);
        query.setSummary(digest.getFiles().getSummary());
        assertTrue(send(ClusterMessageType.FILE_DIGEST, query).getFiles().isSummaryMatch());
        final var fetch = FilePayloads.header(PROJECT, 0L);
        fetch.setPath("/served.txt");
        fetch.setOffset(2);
        fetch.setLength(100);
        final var fetched = send(ClusterMessageType.FILE_FETCH, fetch).getFiles();
        assertEquals("rved", new String(Base64.getDecoder().decode(fetched.getContent()), StandardCharsets.UTF_8));
        assertEquals(Long.toString(version), fetched.getVersion());
        fetch.setLength(0);
        assertEquals(ClusterMessageType.ERROR, send(ClusterMessageType.FILE_FETCH, fetch).getType());
    }

    @Test
    public void test_invalid_or_foreign_payloads_are_refused() throws Exception {
        final var badPath = payload(op("FILE", "/../escape", 1, "x"));
        assertEquals(ClusterMessageType.ERROR, send(ClusterMessageType.REPLICATE_FILES, badPath).getType());
        final var unknownProject = FilePayloads.header("nosuchproject", 0L);
        unknownProject.setOps(List.of());
        assertEquals(ClusterMessageType.ERROR, send(ClusterMessageType.REPLICATE_FILES, unknownProject).getType());
        final var noOps = FilePayloads.header(PROJECT, 0L);
        assertEquals(ClusterMessageType.ERROR, send(ClusterMessageType.REPLICATE_FILES, noOps).getType());
        assertFalse(FileReplicaApplier.isValid(null));
        final var adminProject = FilePayloads.header("admin", 0L);
        assertFalse(FileReplicaApplier.isValid(adminProject));
        final var badVersion = payload(op("FILE", "/a", 0, "x"));
        assertFalse(FileReplicaApplier.isValid(badVersion));
        final var unknownType = payload(op("SYMLINK", "/a", 5, "x"));
        assertTrue(FileReplicaApplier.isValid(unknownType));
        assertFalse(FileReplicaApplier.apply(unknownType, 1_000L));
        assertEquals(-1L, FilePayloads.longOf("x"));
        assertEquals(0L, FilePayloads.longOf(null));
    }

    @Test
    public void test_the_owner_waits_for_a_majority_of_peers() throws Exception {
        assertEquals(ReplicationOutcome.NOT_OWNER, coordinator.replicateFiles(PROJECT, List.of(), null));
        selfOwnsTheFiles();
        final var changes = store.writes(PROJECT).write(ProjectFilePath.parse("/owned.txt"), new byte[]{1, 2},
                new ProjectFileWrites.WriteSpec(FileWriteMode.REPLACE, null, true), ProjectFilesFixture.nextVersion());
        assertEquals(ReplicationOutcome.QUORUM_MET, coordinator.replicateFiles(PROJECT, changes, new byte[]{1, 2}));
    }

    private void selfOwnsTheFiles() throws Exception {
        for (var i = 0; i < 500; i++) {
            cluster.configureMembership(2, node("self", 19990), node("peer-" + i, cluster.serverPort()));
            if (ownership.isOwner(PROJECT, Globals.FILES_LOCK_NAME)) {
                return;
            }
        }
        throw new IllegalStateException("could not make this node the owner of the project's files");
    }
}
