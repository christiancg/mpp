package org.ccg.mpp.ops.req;

import java.util.List;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.req.agg.BaseAggregationStep;

public class ListenRequest extends OperationRequest {
    private List<BaseAggregationStep> aggregationSteps;

    public ListenRequest(String databaseName, String collectionName) {
        super(OperationType.LISTEN, databaseName, collectionName);
    }

    public List<BaseAggregationStep> getAggregationSteps() {
        return aggregationSteps;
    }

    public void setAggregationSteps(List<BaseAggregationStep> aggregationSteps) {
        this.aggregationSteps = aggregationSteps;
    }
}
