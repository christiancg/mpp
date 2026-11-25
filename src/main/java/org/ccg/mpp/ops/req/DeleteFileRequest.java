package org.ccg.mpp.ops.req;

import org.ccg.mpp.ops.OperationType;

public class DeleteFileRequest extends OperationRequest {
    private String path;
    private boolean recursive;

    public DeleteFileRequest() {
        super(OperationType.DELETE_FILE, null, null);
    }

    public DeleteFileRequest(String projectName) {
        super(OperationType.DELETE_FILE, projectName, null);
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public boolean isRecursive() {
        return recursive;
    }

    public void setRecursive(boolean recursive) {
        this.recursive = recursive;
    }
}
