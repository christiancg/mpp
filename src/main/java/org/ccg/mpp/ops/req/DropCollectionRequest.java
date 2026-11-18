package org.ccg.mpp.ops.req;

import org.ccg.mpp.ops.OperationType;

public class DropCollectionRequest extends OperationRequest {
    public DropCollectionRequest(String databaseName, String collectionName) {
        super(OperationType.DROP_COLLECTION, databaseName, collectionName);
    }
}
