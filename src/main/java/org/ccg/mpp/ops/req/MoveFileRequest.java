package org.ccg.mpp.ops.req;

import org.ccg.mpp.ops.OperationType;

public class MoveFileRequest extends OperationRequest {
    private String from;
    private String to;
    private boolean overwrite;
    private Boolean createParents;

    public MoveFileRequest() {
        super(OperationType.MOVE_FILE, null, null);
    }

    public MoveFileRequest(String projectName) {
        super(OperationType.MOVE_FILE, projectName, null);
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getTo() {
        return to;
    }

    public void setTo(String to) {
        this.to = to;
    }

    public boolean isOverwrite() {
        return overwrite;
    }

    public void setOverwrite(boolean overwrite) {
        this.overwrite = overwrite;
    }

    public Boolean getCreateParents() {
        return createParents;
    }

    public void setCreateParents(Boolean createParents) {
        this.createParents = createParents;
    }
}
