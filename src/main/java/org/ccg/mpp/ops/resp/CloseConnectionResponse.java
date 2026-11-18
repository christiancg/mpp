package org.ccg.mpp.ops.resp;

import org.ccg.mpp.config.Globals;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;

public class CloseConnectionResponse extends OperationResponse {
    public CloseConnectionResponse() {
        super(OperationType.CLOSE_CONNECTION, OperationStatus.OK, Globals.CLOSE_CONNECTION_MESSAGE);
    }
}
