package org.ccg.mpp.ops.resp;

import java.util.List;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.internal.JsonSizeEstimator;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;

public class ListEntrypointsResponse extends OperationResponse {
    private final List<JsonObject> entrypoints;

    public ListEntrypointsResponse(String message, List<JsonObject> entrypoints) {
        super(OperationType.LIST_ENTRYPOINTS, OperationStatus.OK, message);
        this.entrypoints = entrypoints;
    }

    public List<JsonObject> getEntrypoints() {
        return entrypoints;
    }

    @Override
    public int estimatedJsonChars() {
        return withPayloadChars(JsonSizeEstimator.estimate(entrypoints));
    }
}
