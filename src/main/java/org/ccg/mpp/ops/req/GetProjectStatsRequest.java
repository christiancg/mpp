package org.ccg.mpp.ops.req;

import org.ccg.mpp.ops.OperationType;

public class GetProjectStatsRequest extends OperationRequest {
    public GetProjectStatsRequest() {
        super(OperationType.GET_PROJECT_STATS, null, null);
    }
}
