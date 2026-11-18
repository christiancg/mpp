package org.ccg.mpp.ops.resp;

import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;

public class StopListenResponse extends OperationResponse {
    public StopListenResponse() {
        super(OperationType.STOP_LISTEN, OperationStatus.OK, "Stopped listening");
    }
}
