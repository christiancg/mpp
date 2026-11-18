package org.ccg.mpp.ops.resp;

import java.util.List;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.internal.JsonSizeEstimator;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;

public class ListSchedulesResponse extends OperationResponse {
    private final List<JsonObject> schedules;

    public ListSchedulesResponse(String message, List<JsonObject> schedules) {
        super(OperationType.LIST_SCHEDULES, OperationStatus.OK, message);
        this.schedules = schedules;
    }

    public List<JsonObject> getSchedules() {
        return schedules;
    }

    @Override
    public int estimatedJsonChars() {
        return withPayloadChars(JsonSizeEstimator.estimate(schedules));
    }
}
