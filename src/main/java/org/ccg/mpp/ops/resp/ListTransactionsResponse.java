package org.ccg.mpp.ops.resp;

import java.util.List;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.internal.JsonSizeEstimator;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;

public class ListTransactionsResponse extends OperationResponse {
    private final List<JsonObject> transactions;

    public ListTransactionsResponse(String message, List<JsonObject> transactions) {
        super(OperationType.LIST_TRANSACTIONS, OperationStatus.OK, message);
        this.transactions = transactions;
    }

    public List<JsonObject> getTransactions() {
        return transactions;
    }

    @Override
    public int estimatedJsonChars() {
        return withPayloadChars(JsonSizeEstimator.estimate(transactions));
    }
}
