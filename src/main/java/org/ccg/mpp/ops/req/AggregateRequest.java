package org.ccg.mpp.ops.req;

import java.util.List;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.req.agg.BaseAggregationStep;

public class AggregateRequest extends OperationRequest {
    private List<BaseAggregationStep> aggregationSteps;
    private boolean analyze;

    public AggregateRequest(String databaseName, String collectionName) {
        super(OperationType.AGGREGATE, databaseName, collectionName);
    }

    public List<BaseAggregationStep> getAggregationSteps() {
        return aggregationSteps;
    }

    public void setAggregationSteps(List<BaseAggregationStep> aggregationSteps) {
        this.aggregationSteps = aggregationSteps;
    }

    public boolean isAnalyze() {
        return analyze;
    }

    public void setAnalyze(boolean analyze) {
        this.analyze = analyze;
    }
}
