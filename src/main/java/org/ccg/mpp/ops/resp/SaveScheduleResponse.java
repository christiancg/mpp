package org.ccg.mpp.ops.resp;

import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;

public class SaveScheduleResponse extends OperationResponse {
    private final long version;

    public SaveScheduleResponse(String message, long version) {
        super(OperationType.SAVE_SCHEDULE, OperationStatus.OK, message);
        this.version = version;
    }

    public long getVersion() {
        return version;
    }
}
