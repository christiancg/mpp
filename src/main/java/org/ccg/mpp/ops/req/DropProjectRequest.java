package org.ccg.mpp.ops.req;

import org.ccg.mpp.ops.OperationType;

public class DropProjectRequest extends OperationRequest {
    public DropProjectRequest(String projectName) {
        super(OperationType.DROP_PROJECT, projectName, null);
    }
}
