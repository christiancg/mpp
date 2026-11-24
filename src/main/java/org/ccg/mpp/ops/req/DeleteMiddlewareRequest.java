package org.ccg.mpp.ops.req;

import org.ccg.mpp.ops.OperationType;

public class DeleteMiddlewareRequest extends OperationRequest {
    private String name;

    public DeleteMiddlewareRequest() {
        super(OperationType.DELETE_MIDDLEWARE, null, null);
    }

    public DeleteMiddlewareRequest(String projectName, String name) {
        super(OperationType.DELETE_MIDDLEWARE, projectName, null);
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
