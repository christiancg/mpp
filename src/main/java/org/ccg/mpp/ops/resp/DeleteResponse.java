package org.ccg.mpp.ops.resp;

import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;

public class DeleteResponse extends OperationResponse {
    public DeleteResponse(String message) {
        super(OperationType.DELETE, OperationStatus.OK, message);
    }
}
