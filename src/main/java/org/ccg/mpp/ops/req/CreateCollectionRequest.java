package org.ccg.mpp.ops.req;

import org.ccg.mpp.ops.OperationType;

public class CreateCollectionRequest extends OperationRequest {
    public CreateCollectionRequest(String databaseName, String collectionName) {
        super(OperationType.CREATE_COLLECTION, databaseName, collectionName);
    }
}
