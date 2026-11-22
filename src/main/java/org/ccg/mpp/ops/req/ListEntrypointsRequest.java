package org.ccg.mpp.ops.req;

import org.ccg.mpp.ops.OperationType;

public class ListEntrypointsRequest extends OperationRequest {
    public ListEntrypointsRequest() {
        super(OperationType.LIST_ENTRYPOINTS, null, null);
    }

    public ListEntrypointsRequest(String projectName) {
        super(OperationType.LIST_ENTRYPOINTS, projectName, null);
    }
}
