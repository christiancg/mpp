package org.ccg.mpp.ops.files;

import java.util.List;
import org.ccg.mpp.cluster.ClusterCoordinator;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.fs.FileChange;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.resp.OperationResponse;

final class FileReplication {
    private static final ClusterCoordinator coordinator = IocContainer.get(ClusterCoordinator.class);

    private FileReplication() {
    }

    static OperationResponse refusal(OperationType type, String projectName) {
        final var guard = coordinator.guardWrite(projectName, Globals.FILES_LOCK_NAME);
        return switch (guard.kind()) {
            case ALLOW -> null;
            case NO_QUORUM -> new OperationResponse(type, ErrorCode.NO_QUORUM);
            case NOT_OWNER -> new OperationResponse(type, ErrorCode.NOT_COLLECTION_OWNER,
                    "owner of the project's files=" + guard.ownerAddress());
        };
    }

    static OperationResponse replicate(OperationType type, String projectName, List<FileChange> changes,
            byte[] content) {
        if (changes.isEmpty()) {
            return null;
        }
        return switch (coordinator.replicateFiles(projectName, changes, content)) {
            case TIMEOUT -> new OperationResponse(type, ErrorCode.REPLICATION_TIMEOUT);
            case NOT_OWNER, NOT_COORDINATOR -> new OperationResponse(type, ErrorCode.WRITE_OUTCOME_UNKNOWN);
            case NOT_CLUSTERED, QUORUM_MET -> null;
        };
    }
}
