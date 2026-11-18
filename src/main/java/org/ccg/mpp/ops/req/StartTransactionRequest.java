package org.ccg.mpp.ops.req;

import org.ccg.mpp.ops.OperationType;

public class StartTransactionRequest extends OperationRequest {
    public StartTransactionRequest() {
        super(OperationType.START_TRANSACTION, null, null);
    }
}
