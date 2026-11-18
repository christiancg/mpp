package org.ccg.mpp.ops.resp;

import java.util.List;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.internal.JsonSizeEstimator;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;

public class ListTriggerRunsResponse extends OperationResponse {
    private final List<JsonObject> runs;

    public ListTriggerRunsResponse(String message, List<JsonObject> runs) {
        super(OperationType.LIST_TRIGGER_RUNS, OperationStatus.OK, message);
        this.runs = runs;
    }

    public List<JsonObject> getRuns() {
        return runs;
    }

    @Override
    public int estimatedJsonChars() {
        return withPayloadChars(JsonSizeEstimator.estimate(runs));
    }
}
