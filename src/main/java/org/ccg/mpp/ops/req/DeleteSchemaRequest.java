package org.ccg.mpp.ops.req;

import org.ccg.mpp.ops.OperationType;

public class DeleteSchemaRequest extends OperationRequest {
    public DeleteSchemaRequest(String projectName, String collectionName) {
        super(OperationType.DELETE_SCHEMA, projectName, collectionName);
    }
}
