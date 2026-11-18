package org.ccg.mpp.ops.req;

import org.ccg.mpp.ops.OperationType;

public class ListTriggersRequest extends OperationRequest {
    public ListTriggersRequest() {
        super(OperationType.LIST_TRIGGERS, null, null);
    }

    public ListTriggersRequest(String databaseName, String collectionName) {
        super(OperationType.LIST_TRIGGERS, databaseName, collectionName);
    }
}
