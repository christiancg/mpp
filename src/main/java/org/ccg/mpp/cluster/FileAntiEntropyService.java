package org.ccg.mpp.cluster;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.cluster.files.FilePayloads;
import org.ccg.mpp.cluster.files.FileReplicaApplier;
import org.ccg.mpp.cluster.membership.MembershipService;
import org.ccg.mpp.cluster.msg.ClusterMessage;
import org.ccg.mpp.cluster.msg.ClusterMessageType;
import org.ccg.mpp.cluster.msg.FilePayload;
import org.ccg.mpp.cluster.msg.FileReplicationOp;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.fs.ProjectFileReplica;
import org.ccg.mpp.fs.ProjectFileStore;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.log.Logger;

public class FileAntiEntropyService implements MembershipListener {
    private final Logger logger = Logger.logFor(FileAntiEntropyService.class);
    private final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final PeerConnectionPool pool = IocContainer.get(PeerConnectionPool.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final ProjectFileStore store = IocContainer.get(ProjectFileStore.class);
    private final CoalescingSweep sweep = new CoalescingSweep(logger, "cluster-file-anti-entropy", "File anti-entropy",
            this::reconcileAllProjects);

    public void start() {
        if (clusterConfig.isEnabled()) {
            sweep.startPeriodic(clusterConfig.antiEntropyIntervalMs());
        }
    }

    public void stop(long awaitMillis) {
        sweep.stop(awaitMillis);
    }

    @Override
    public void onMembershipChanged(MembershipView view) {
        reconcileNow();
    }

    public void reconcileNow() {
        if (clusterConfig.isEnabled()) {
            sweep.schedule();
        }
    }

    private void reconcileAllProjects() {
        for (final var projectName : cache.getUserProjectNames()) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            try {
                reconcile(projectName);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                logger.warning("File anti-entropy of project '" + projectName + "' failed: " + e.getMessage());
            }
        }
    }

    void reconcile(String projectName) throws Exception {
        final var peers = membershipService.membershipView().peers(membershipService.getSelf());
        final var wait = clusterConfig.replicationAckTimeoutMs();
        var answered = 0;
        for (final var peer : peers) {
            final var local = FileReplicaApplier.listing(projectName, wait);
            final var query = FilePayloads.header(projectName, store.incarnation(projectName, wait));
            query.setSummary(FilePayloads.summary(local));
            final var answer = send(peer.address(), ClusterMessageType.FILE_DIGEST, query,
                    ClusterMessageType.FILE_DIGEST_ACK);
            if (answer == null || answer.getFiles() == null) {
                continue;
            }
            answered++;
            if (!answer.getFiles().isSummaryMatch() && answer.getFiles().getOps() != null) {
                pullNewer(peer.address(), projectName, local, answer.getFiles().getOps());
            }
        }
        if (!peers.isEmpty() && answered == peers.size()) {
            store.collectTombstones(projectName,
                    HybridClock.pack(System.currentTimeMillis() - clusterConfig.tombstoneRetentionMs(), 0), wait);
        }
    }

    private void pullNewer(NodeAddress peer, String projectName, List<FileReplicationOp> local,
            List<FileReplicationOp> remote) throws Exception {
        final Map<String, Long> localVersions = new HashMap<>();
        for (final var op : local) {
            localVersions.merge(op.getPath(), FilePayloads.longOf(op.getVersion()), Math::max);
        }
        final var newer = new ArrayList<FileReplicationOp>();
        for (final var op : remote) {
            if (FilePayloads.longOf(op.getVersion()) > localVersions.getOrDefault(op.getPath(), 0L)) {
                newer.add(op);
            }
        }
        newer.sort(Comparator.comparing((FileReplicationOp op) -> FilePayloads.TOMBSTONE.equals(op.getType()))
                .thenComparing(
                        op -> FilePayloads.TOMBSTONE.equals(op.getType()) ? invert(op.getPath()) : op.getPath()));
        final var replica = store.replica(projectName, clusterConfig.replicationAckTimeoutMs());
        for (final var op : newer) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            pullOne(peer, projectName, replica, op);
        }
    }

    private static String invert(String path) {
        final var inverted = new StringBuilder(path.length());
        for (var i = 0; i < path.length(); i++) {
            inverted.append((char) (Character.MAX_VALUE - path.charAt(i)));
        }
        return inverted.toString();
    }

    private void pullOne(NodeAddress peer, String projectName, ProjectFileReplica replica, FileReplicationOp op)
            throws Exception {
        final var version = FilePayloads.longOf(op.getVersion());
        final var applied = switch (op.getType()) {
            case "DIRECTORY" -> replica.applyDirectory(op.getPath(), version);
            case FilePayloads.TOMBSTONE -> replica.applyTombstone(op.getPath(), version);
            case "FILE" -> {
                final var content = fetch(peer, projectName, op.getPath(), version, op.getSize());
                yield content != null && replica.applyFile(op.getPath(), version, content);
            }
            default -> false;
        };
        if (applied) {
            IocContainer.get(HybridClock.class).observe(version);
        } else {
            logger.info("File anti-entropy could not take " + op.getPath() + " of project '" + projectName + "' from "
                    + peer + " yet; the next round retries it");
        }
    }

    private byte[] fetch(NodeAddress peer, String projectName, String path, long version, long size) throws Exception {
        final var content = new ByteArrayOutputStream((int) Math.min(size, Integer.MAX_VALUE));
        var offset = 0L;
        while (offset < size) {
            final var query = FilePayloads.header(projectName,
                    store.incarnation(projectName, clusterConfig.replicationAckTimeoutMs()));
            query.setPath(path);
            query.setOffset(offset);
            query.setLength((int) Math.min(Globals.FILE_FETCH_CHUNK_BYTES, size - offset));
            final var answer = send(peer, ClusterMessageType.FILE_FETCH, query, ClusterMessageType.FILE_FETCH_ACK);
            final var files = answer == null ? null : answer.getFiles();
            if (files == null || FilePayloads.longOf(files.getVersion()) != version || files.getContent() == null) {
                return null;
            }
            final var chunk = Base64.getDecoder().decode(files.getContent());
            if (chunk.length == 0) {
                return null;
            }
            content.write(chunk);
            offset += chunk.length;
        }
        return content.toByteArray();
    }

    private ClusterMessage send(NodeAddress address, ClusterMessageType type, FilePayload payload,
            ClusterMessageType expected) throws InterruptedException {
        final var message = new ClusterMessage(null, type, clusterConfig.secret(), membershipService.getSelf(), null);
        message.setFiles(payload);
        try {
            final var response = pool.request(address, message, clusterConfig.replicationAckTimeoutMs());
            if (response.getType() == expected) {
                return response;
            }
            logger.warning(
                    "File anti-entropy request to " + address + " not acknowledged: " + response.getErrorMessage());
            return null;
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            logger.warning("File anti-entropy request to " + address + " failed: " + e.getMessage());
            return null;
        }
    }
}
