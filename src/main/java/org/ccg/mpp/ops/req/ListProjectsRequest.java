package org.ccg.mpp.ops.req;

import org.ccg.mpp.ops.OperationType;

public class ListProjectsRequest extends OperationRequest {
    public ListProjectsRequest() {
        super(OperationType.LIST_PROJECTS, null, null);
    }
}
