package org.ccg.mpp.ops.req;

import org.ccg.mpp.ops.OperationType;

public class ListMiddlewaresRequest extends OperationRequest {
    public ListMiddlewaresRequest() {
        super(OperationType.LIST_MIDDLEWARES, null, null);
    }

    public ListMiddlewaresRequest(String projectName) {
        super(OperationType.LIST_MIDDLEWARES, projectName, null);
    }
}
