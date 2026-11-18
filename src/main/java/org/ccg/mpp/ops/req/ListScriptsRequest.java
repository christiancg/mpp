package org.ccg.mpp.ops.req;

import org.ccg.mpp.ops.OperationType;

public class ListScriptsRequest extends OperationRequest {
    public ListScriptsRequest() {
        super(OperationType.LIST_SCRIPTS, null, null);
    }
}
