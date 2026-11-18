package org.ccg.mpp.ops.resp;

import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;

public class StartTransactionResponse extends OperationResponse {
    public String transactionId;

    public StartTransactionResponse(String message, String transactionId) {
        super(OperationType.START_TRANSACTION, OperationStatus.OK, message);
        this.transactionId = transactionId;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }
}
