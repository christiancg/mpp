package org.ccg.mpp.ops.resp;

import java.util.List;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.internal.JsonSizeEstimator;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;

public class ListTriggersResponse extends OperationResponse {
    private final List<JsonObject> triggers;

    public ListTriggersResponse(String message, List<JsonObject> triggers) {
        super(OperationType.LIST_TRIGGERS, OperationStatus.OK, message);
        this.triggers = triggers;
    }

    public List<JsonObject> getTriggers() {
        return triggers;
    }

    @Override
    public int estimatedJsonChars() {
        return withPayloadChars(JsonSizeEstimator.estimate(triggers));
    }
}
