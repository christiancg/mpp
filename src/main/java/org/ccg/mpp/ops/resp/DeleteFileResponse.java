package org.ccg.mpp.ops.resp;

import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;

public class DeleteFileResponse extends OperationResponse {
    private final int deleted;

    public DeleteFileResponse(int deleted) {
        super(OperationType.DELETE_FILE, OperationStatus.OK, "Files deleted");
        this.deleted = deleted;
    }

    public int getDeleted() {
        return deleted;
    }
}
