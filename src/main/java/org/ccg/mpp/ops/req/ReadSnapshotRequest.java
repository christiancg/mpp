package org.ccg.mpp.ops.req;

import org.ccg.mpp.ops.OperationType;

public class ReadSnapshotRequest extends OperationRequest {
    private String snapshotId;
    private String afterId;
    private int limit;

    public ReadSnapshotRequest(String projectName, String collectionName) {
        super(OperationType.READ_SNAPSHOT, projectName, collectionName);
    }

    public String getSnapshotId() {
        return snapshotId;
    }

    public void setSnapshotId(String snapshotId) {
        this.snapshotId = snapshotId;
    }

    public String getAfterId() {
        return afterId;
    }

    public void setAfterId(String afterId) {
        this.afterId = afterId;
    }

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }
}
