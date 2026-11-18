package org.ccg.mpp.ops.req;

import org.ccg.mpp.ops.OperationType;

public class CloseConnectionRequest extends OperationRequest {
    public CloseConnectionRequest() {
        super(OperationType.CLOSE_CONNECTION, null, null);
    }
}
