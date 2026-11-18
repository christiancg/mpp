package org.ccg.mpp.ops.req;

import org.ccg.mpp.ops.OperationType;

public class RollbackTransactionRequest extends OperationRequest {
    public RollbackTransactionRequest() {
        super(OperationType.ROLLBACK_TRANSACTION, null, null);
    }
}
