package org.ccg.mpp.ops.resp;

import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;

public class SaveProcedureResponse extends OperationResponse {
    private final long version;

    public SaveProcedureResponse(String message, long version) {
        super(OperationType.SAVE_PROCEDURE, OperationStatus.OK, message);
        this.version = version;
    }

    public long getVersion() {
        return version;
    }
}
