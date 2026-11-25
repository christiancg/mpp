package org.ccg.mpp.ops.req;

import org.ccg.mpp.ops.OperationType;

public class MakeDirectoryRequest extends OperationRequest {
    private String path;
    private Boolean recursive;

    public MakeDirectoryRequest() {
        super(OperationType.MAKE_DIRECTORY, null, null);
    }

    public MakeDirectoryRequest(String projectName) {
        super(OperationType.MAKE_DIRECTORY, projectName, null);
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public Boolean getRecursive() {
        return recursive;
    }

    public void setRecursive(Boolean recursive) {
        this.recursive = recursive;
    }
}
