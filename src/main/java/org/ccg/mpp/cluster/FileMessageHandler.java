package org.ccg.mpp.cluster;

import java.io.IOException;
import java.util.Base64;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.cluster.files.FilePayloads;
import org.ccg.mpp.cluster.files.FileReplicaApplier;
import org.ccg.mpp.cluster.msg.ClusterMessage;
import org.ccg.mpp.cluster.msg.ClusterMessageType;
import org.ccg.mpp.cluster.msg.FilePayload;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.ProjectFilePath;
import org.ccg.mpp.ex.ProjectFilesBusyException;
import org.ccg.mpp.fs.ProjectFileStore;
import org.ccg.mpp.ioc.IocContainer;

final class FileMessageHandler {
    private static final ProjectFileStore store = IocContainer.get(ProjectFileStore.class);
    private static final FileAntiEntropyService fileAntiEntropy = IocContainer.get(FileAntiEntropyService.class);
    private static final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);

    private FileMessageHandler() {
    }

    static String laneKeyOf(ClusterMessage request) {
        final var payload = request.getFiles();
        return FileReplicaApplier.isValid(payload)
                ? Cache.getCollectionIdentifier(payload.getProjectName(), Globals.FILES_LOCK_NAME)
                : null;
    }

    static ClusterMessage handle(ClusterMessage request) {
        final var payload = request.getFiles();
        if (!FileReplicaApplier.isValid(payload)) {
            return ClusterMessages.invalidPayload();
        }
        if (!FileReplicaApplier.sameLife(payload)) {
            return error("The project '" + payload.getProjectName() + "' is unknown here, or another life of it");
        }
        return switch (request.getType()) {
            case REPLICATE_FILES -> replicate(payload);
            case FILE_DIGEST -> digest(payload);
            case FILE_FETCH -> fetch(payload);
            default -> error("Unsupported file message " + request.getType());
        };
    }

    private static ClusterMessage replicate(FilePayload payload) {
        if (payload.getOps() == null) {
            return ClusterMessages.invalidPayload();
        }
        return ClusterMessages.reply(ClusterMessageType.REPLICATE_FILES_ACK, "Failed to apply replicated files",
                response -> {
                    if (!appliedWithin(payload)) {
                        fileAntiEntropy.reconcileNow();
                        response.setType(ClusterMessageType.ERROR);
                        response.setErrorMessage("Could not apply every replicated file change; a file"
                                + " anti-entropy round fetches the rest");
                    }
                });
    }

    private static boolean appliedWithin(FilePayload payload) throws IOException {
        try {
            return FileReplicaApplier.apply(payload, clusterConfig.replicationAckTimeoutMs());
        } catch (ProjectFilesBusyException busy) {
            return false;
        }
    }

    private static ClusterMessage digest(FilePayload query) {
        return ClusterMessages.reply(ClusterMessageType.FILE_DIGEST_ACK, "Failed to describe the files", response -> {
            final var wait = clusterConfig.replicationAckTimeoutMs();
            final var listing = FileReplicaApplier.listing(query.getProjectName(), wait);
            final var answer = FilePayloads.header(query.getProjectName(),
                    store.incarnation(query.getProjectName(), wait));
            final var summary = FilePayloads.summary(listing);
            answer.setSummary(summary);
            if (summary.equals(query.getSummary())) {
                answer.setSummaryMatch(true);
            } else {
                answer.setOps(listing);
            }
            response.setFiles(answer);
        });
    }

    private static ClusterMessage fetch(FilePayload query) {
        if (query.getPath() == null || query.getLength() <= 0 || query.getOffset() < 0) {
            return ClusterMessages.invalidPayload();
        }
        return ClusterMessages.reply(ClusterMessageType.FILE_FETCH_ACK, "Failed to read the file", response -> {
            final var length = Math.min(query.getLength(), Globals.FILE_FETCH_CHUNK_BYTES);
            final var wait = clusterConfig.replicationAckTimeoutMs();
            final var slice = store.read(query.getProjectName(), ProjectFilePath.ofStored(query.getPath()),
                    query.getOffset(), length, wait);
            final var answer = FilePayloads.header(query.getProjectName(),
                    store.incarnation(query.getProjectName(), wait));
            answer.setPath(query.getPath());
            answer.setVersion(Long.toString(slice.entry().version()));
            answer.setSize(slice.entry().size());
            answer.setOffset(slice.offset());
            answer.setContent(Base64.getEncoder().encodeToString(slice.bytes()));
            response.setFiles(answer);
        });
    }

    private static ClusterMessage error(String message) {
        final var response = new ClusterMessage();
        response.setType(ClusterMessageType.ERROR);
        response.setErrorMessage(message);
        return response;
    }
}
