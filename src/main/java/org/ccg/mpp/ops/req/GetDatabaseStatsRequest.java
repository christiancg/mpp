package org.ccg.mpp.ops.req;

import org.ccg.mpp.ops.OperationType;

public class GetDatabaseStatsRequest extends OperationRequest {
    public GetDatabaseStatsRequest() {
        super(OperationType.GET_DATABASE_STATS, null, null);
    }
}
