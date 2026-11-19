package org.ccg.mpp.ops.req;

import org.ccg.mpp.ops.OperationType;

public class GetSchemaRequest extends OperationRequest {
    public GetSchemaRequest(String projectName, String collectionName) {
        super(OperationType.GET_SCHEMA, projectName, collectionName);
    }
}
