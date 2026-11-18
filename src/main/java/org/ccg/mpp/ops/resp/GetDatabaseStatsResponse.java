package org.ccg.mpp.ops.resp;

import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.internal.JsonSizeEstimator;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;

public class GetDatabaseStatsResponse extends OperationResponse {
    private final JsonObject stats;

    public GetDatabaseStatsResponse(String message, JsonObject stats) {
        super(OperationType.GET_DATABASE_STATS, OperationStatus.OK, message);
        this.stats = stats;
    }

    public JsonObject getStats() {
        return stats;
    }

    @Override
    public int estimatedJsonChars() {
        return withPayloadChars(JsonSizeEstimator.estimate(stats));
    }
}
