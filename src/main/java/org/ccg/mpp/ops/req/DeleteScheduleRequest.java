package org.ccg.mpp.ops.req;

import org.ccg.mpp.ops.OperationType;

public class DeleteScheduleRequest extends OperationRequest {
    private String name;

    public DeleteScheduleRequest() {
        super(OperationType.DELETE_SCHEDULE, null, null);
    }

    public DeleteScheduleRequest(String projectName, String name) {
        super(OperationType.DELETE_SCHEDULE, projectName, null);
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
