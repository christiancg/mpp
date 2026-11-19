package org.ccg.mpp.ops.req;

import org.ccg.mpp.ops.OperationType;

public class ReleaseSnapshotRequest extends OperationRequest {
    private String snapshotId;

    public ReleaseSnapshotRequest(String projectName) {
        super(OperationType.RELEASE_SNAPSHOT, projectName, null);
    }

    public String getSnapshotId() {
        return snapshotId;
    }

    public void setSnapshotId(String snapshotId) {
        this.snapshotId = snapshotId;
    }
}
