package org.ccg.mpp.ops.req;

import org.ccg.mpp.ops.OperationType;

public class ListTriggerRunsRequest extends OperationRequest {
    private String status;

    public ListTriggerRunsRequest() {
        super(OperationType.LIST_TRIGGER_RUNS, null, null);
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
