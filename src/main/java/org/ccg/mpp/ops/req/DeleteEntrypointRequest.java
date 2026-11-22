package org.ccg.mpp.ops.req;

import org.ccg.mpp.ops.OperationType;

public class DeleteEntrypointRequest extends OperationRequest {
    private String name;

    public DeleteEntrypointRequest() {
        super(OperationType.DELETE_ENTRYPOINT, null, null);
    }

    public DeleteEntrypointRequest(String projectName, String name) {
        super(OperationType.DELETE_ENTRYPOINT, projectName, null);
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
