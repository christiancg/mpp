package org.ccg.mpp.ops.req;

import org.ccg.mpp.ops.OperationType;

public class CommitTransactionRequest extends OperationRequest {
    public CommitTransactionRequest() {
        super(OperationType.COMMIT_TRANSACTION, null, null);
    }
}
