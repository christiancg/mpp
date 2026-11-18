package org.ccg.mpp.ops.resp;

import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;

public class CancelScriptResponse extends OperationResponse {
    private final boolean cancelled;

    public CancelScriptResponse(String message, boolean cancelled) {
        super(OperationType.CANCEL_SCRIPT, OperationStatus.OK, message);
        this.cancelled = cancelled;
    }

    public boolean isCancelled() {
        return cancelled;
    }
}
