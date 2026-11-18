package org.ccg.mpp.ops.resp;

import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;

public class ResolveTriggerRunResponse extends OperationResponse {
    private final boolean resolved;

    public ResolveTriggerRunResponse(String message, boolean resolved) {
        super(OperationType.RESOLVE_TRIGGER_RUN, OperationStatus.OK, message);
        this.resolved = resolved;
    }

    public boolean isResolved() {
        return resolved;
    }
}
