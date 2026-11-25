package org.ccg.mpp.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.ccg.mpp.cluster.files.FilePayloads;
import org.ccg.mpp.cluster.membership.MembershipService;
import org.ccg.mpp.cluster.msg.ClusterMessage;
import org.ccg.mpp.cluster.msg.ClusterMessageType;
import org.ccg.mpp.cluster.msg.FilePayload;
import org.ccg.mpp.cluster.msg.FileReplicationOp;
import org.ccg.mpp.config.ConfigKey;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.data.ProjectFilePath;
import org.ccg.mpp.ex.ProjectFilesBusyException;
import org.ccg.mpp.fs.ManifestLockHolder;
import org.ccg.mpp.fs.ProjectFileReplica;
import org.ccg.mpp.fs.ProjectFileStore;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.test.ProjectFilesFixture;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;
import org.mockito.ArgumentMatchers;

public class FileAntiEntropyServiceTest {
    private static final String PROJECT = TestGlobals.PROJECT;
    private final FileAntiEntropyService service = IocContainer.get(FileAntiEntropyService.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final Configuration configuration = Configuration.getInstance();
    private PeerConnectionPool realPool;
    private PeerConnectionPool pool;
    private ProjectFileStore store;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
        store = ProjectFilesFixture.start();
        configuration.override(ConfigKey.CLUSTER_ENABLED, "true");
        final var self = new NodeInfo("self", "127.0.0.1", 19990, NodeState.ALIVE, 1L, 1L);
        final var peer = new NodeInfo("peer", "127.0.0.1", 19991, NodeState.ALIVE, 1L, 1L);
        final var members = new ConcurrentHashMap<String, NodeInfo>();
        members.put("self", self);
        members.put("peer", peer);
        TestUtils.setPrivateField(membershipService, "members", members);
        TestUtils.setPrivateField(membershipService, "self", self);
        realPool = TestUtils.getPrivateField(service, "pool", PeerConnectionPool.class);
        pool = mock(PeerConnectionPool.class);
        TestUtils.setPrivateField(service, "pool", pool);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.setPrivateField(service, "pool", realPool);
        TestUtils.setPrivateField(membershipService, "members", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(membershipService, "self", null);
        ProjectFilesFixture.stop();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static FileReplicationOp op(String type, String path, long version, long size) {
        final var op = new FileReplicationOp();
        op.setType(type);
        op.setPath(path);
        op.setVersion(Long.toString(version));
        op.setSize(size);
        return op;
    }

    private static ClusterMessage ack(ClusterMessageType type, FilePayload payload) {
        final var message = new ClusterMessage();
        message.setType(type);
        message.setFiles(payload);
        return message;
    }

    private static ArgumentMatcher<ClusterMessage> ofType(ClusterMessageType type) {
        return message -> message != null && message.getType() == type;
    }

    private void peerAnswers(List<FileReplicationOp> listing, long fetchedVersion, String content) throws Exception {
        final var digest = FilePayloads.header(PROJECT, 0L);
        digest.setOps(listing);
        digest.setSummary("peer");
        when(pool.request(any(), ArgumentMatchers.argThat(ofType(ClusterMessageType.FILE_DIGEST)), anyLong()))
                .thenReturn(ack(ClusterMessageType.FILE_DIGEST_ACK, digest));
        final var fetched = FilePayloads.header(PROJECT, 0L);
        fetched.setVersion(Long.toString(fetchedVersion));
        fetched.setContent(Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
        when(pool.request(any(), ArgumentMatchers.argThat(ofType(ClusterMessageType.FILE_FETCH)), anyLong()))
                .thenReturn(ack(ClusterMessageType.FILE_FETCH_ACK, fetched));
    }

    private String pulledFile() throws Exception {
        return new String(store.read(PROJECT, ProjectFilePath.parse("/d/f"), 0, 1024).bytes(), StandardCharsets.UTF_8);
    }

    @Test
    public void test_newer_peer_entries_are_pulled_in_path_order() throws Exception {
        final var version = ProjectFilesFixture.nextVersion();
        final var listing = new ArrayList<>(List.of(op("FILE", "/d/f", version + 1, 3),
                op("DIRECTORY", "/d", version, 0), op(FilePayloads.TOMBSTONE, "/gone", version + 2, 0)));
        peerAnswers(listing, version + 1, "abc");
        service.reconcile(PROJECT);
        assertEquals("abc", pulledFile());
        assertTrue(store.replica(PROJECT).describe().stream()
                .anyMatch(entry -> entry.tombstone() && entry.path().equals("/gone")));
    }

    @Test
    public void test_a_file_that_changed_on_the_peer_during_the_fetch_waits_for_the_next_round() throws Exception {
        final var version = ProjectFilesFixture.nextVersion();
        peerAnswers(List.of(op("FILE", "/f", version, 3)), version + 5, "xyz");
        service.reconcile(PROJECT);
        assertNull(store.stat(PROJECT, ProjectFilePath.parse("/f")));
    }

    @Test
    public void test_matching_summaries_pull_nothing_and_old_tombstones_are_collected() throws Exception {
        store.replica(PROJECT).applyTombstone("/ancient", 1L);
        final var match = FilePayloads.header(PROJECT, 0L);
        match.setSummaryMatch(true);
        when(pool.request(any(), any(), anyLong())).thenReturn(ack(ClusterMessageType.FILE_DIGEST_ACK, match));
        service.reconcile(PROJECT);
        verify(pool, never()).request(any(), ArgumentMatchers.argThat(ofType(ClusterMessageType.FILE_FETCH)),
                anyLong());
        assertEquals(List.<ProjectFileReplica.Described>of(), store.replica(PROJECT).describe());
    }

    @Test
    public void test_a_round_gives_up_on_files_that_stay_locked() throws Exception {
        final var previous = configuration.longValue(ConfigKey.REPLICATION_ACK_TIMEOUT_MS);
        configuration.override(ConfigKey.REPLICATION_ACK_TIMEOUT_MS, "100");
        try (var _ = ManifestLockHolder.hold(store, PROJECT)) {
            assertThrows(ProjectFilesBusyException.class, () -> service.reconcile(PROJECT));
        } finally {
            configuration.override(ConfigKey.REPLICATION_ACK_TIMEOUT_MS, Long.toString(previous));
        }
        verify(pool, never()).request(any(), any(), anyLong());
    }

    @Test
    public void test_an_unreachable_peer_keeps_the_tombstones() throws Exception {
        store.replica(PROJECT).applyTombstone("/ancient", 1L);
        when(pool.request(any(), any(), anyLong())).thenThrow(new IOException("down"));
        service.reconcile(PROJECT);
        assertEquals(1, store.replica(PROJECT).describe().size());
        final var refused = new ClusterMessage();
        refused.setType(ClusterMessageType.ERROR);
        doReturn(refused).when(pool).request(any(), any(), anyLong());
        service.reconcile(PROJECT);
        assertEquals(1, store.replica(PROJECT).describe().size());
    }
}
