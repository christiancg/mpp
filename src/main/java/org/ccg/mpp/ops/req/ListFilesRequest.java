package org.ccg.mpp.ops.req;

import org.ccg.mpp.ops.OperationType;

public class ListFilesRequest extends OperationRequest {
    private String path;
    private boolean recursive;
    private Integer limit;
    private String after;

    public ListFilesRequest() {
        super(OperationType.LIST_FILES, null, null);
    }

    public ListFilesRequest(String projectName) {
        super(OperationType.LIST_FILES, projectName, null);
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

    public Integer getLimit() {
        return limit;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }

    public String getAfter() {
        return after;
    }

    public void setAfter(String after) {
        this.after = after;
    }
}
