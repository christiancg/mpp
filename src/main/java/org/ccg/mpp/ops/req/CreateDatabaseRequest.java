package org.ccg.mpp.ops.req;

import org.ccg.mpp.ops.OperationType;

public class CreateDatabaseRequest extends OperationRequest {
    public CreateDatabaseRequest(String databaseName) {
        super(OperationType.CREATE_DATABASE, databaseName, null);
    }
}
