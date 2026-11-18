package org.ccg.mpp.ops.req;

import java.util.ArrayList;
import java.util.List;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.req.agg.BaseAggregationStep;

public class ListUsersRequest extends OperationRequest {
    private List<BaseAggregationStep> aggregationSteps;

    public ListUsersRequest() {
        super(OperationType.LIST_USERS, null, null);
        this.aggregationSteps = new ArrayList<>();
    }

    public List<BaseAggregationStep> getAggregationSteps() {
        return aggregationSteps != null ? aggregationSteps : new ArrayList<>();
    }

    public void setAggregationSteps(List<BaseAggregationStep> aggregationSteps) {
        this.aggregationSteps = aggregationSteps;
    }
}
