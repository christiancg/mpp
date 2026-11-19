package org.ccg.mpp.ops.req;

import org.ccg.mpp.ops.OperationType;

public class DeleteProcedureRequest extends OperationRequest {
    private String name;

    public DeleteProcedureRequest() {
        super(OperationType.DELETE_PROCEDURE, null, null);
    }

    public DeleteProcedureRequest(String projectName, String name) {
        super(OperationType.DELETE_PROCEDURE, projectName, null);
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
