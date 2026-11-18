package org.ccg.mpp.ops.req;

import org.ccg.mpp.ops.OperationType;

public class ListDatabasesRequest extends OperationRequest {
    public ListDatabasesRequest() {
        super(OperationType.LIST_DATABASES, null, null);
    }
}
