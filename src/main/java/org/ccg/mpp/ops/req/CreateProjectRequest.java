package org.ccg.mpp.ops.req;

import org.ccg.mpp.ops.OperationType;

public class CreateProjectRequest extends OperationRequest {
    public CreateProjectRequest(String projectName) {
        super(OperationType.CREATE_PROJECT, projectName, null);
    }
}
