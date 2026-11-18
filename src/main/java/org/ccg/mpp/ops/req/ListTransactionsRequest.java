package org.ccg.mpp.ops.req;

import org.ccg.mpp.ops.OperationType;

public class ListTransactionsRequest extends OperationRequest {
    public ListTransactionsRequest() {
        super(OperationType.LIST_TRANSACTIONS, null, null);
    }
}
