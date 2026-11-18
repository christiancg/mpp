package org.ccg.mpp.ops.req;

import org.ccg.mpp.ops.OperationType;

public class ListCollectionsRequest extends OperationRequest {
    public ListCollectionsRequest(String databaseName) {
        super(OperationType.LIST_COLLECTIONS, databaseName, null);
    }
}
