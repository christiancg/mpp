package org.ccg.mpp.ops.req;

import org.ccg.mpp.ops.OperationType;

public class ListSchedulesRequest extends OperationRequest {
    private boolean includeArgs;

    public ListSchedulesRequest() {
        super(OperationType.LIST_SCHEDULES, null, null);
    }

    public ListSchedulesRequest(String projectName) {
        super(OperationType.LIST_SCHEDULES, projectName, null);
    }

    public boolean isIncludeArgs() {
        return includeArgs;
    }

    public void setIncludeArgs(boolean includeArgs) {
        this.includeArgs = includeArgs;
    }
}
