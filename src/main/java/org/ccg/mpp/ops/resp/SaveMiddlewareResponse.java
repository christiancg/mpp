package org.ccg.mpp.ops.resp;

import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;

public class SaveMiddlewareResponse extends OperationResponse {
    private final long version;
    private final long procedureVersion;

    public SaveMiddlewareResponse(String message, long version, long procedureVersion) {
        super(OperationType.SAVE_MIDDLEWARE, OperationStatus.OK, message);
        this.version = version;
        this.procedureVersion = procedureVersion;
    }

    public long getVersion() {
        return version;
    }

    public long getProcedureVersion() {
        return procedureVersion;
    }
}
