package org.ccg.mpp.ops.req;

import org.ccg.mpp.ops.OperationType;

public class StatFileRequest extends OperationRequest {
    private String path;

    public StatFileRequest() {
        super(OperationType.STAT_FILE, null, null);
    }

    public StatFileRequest(String projectName) {
        super(OperationType.STAT_FILE, projectName, null);
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }
}
