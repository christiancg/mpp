package org.ccg.mpp.ops.req;

import org.ccg.mpp.ops.OperationType;

public class DropDatabaseRequest extends OperationRequest {
    public DropDatabaseRequest(String databaseName) {
        super(OperationType.DROP_DATABASE, databaseName, null);
    }
}
