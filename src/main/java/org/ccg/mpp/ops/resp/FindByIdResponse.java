package org.ccg.mpp.ops.resp;

import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.internal.JsonSizeEstimator;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;

public class FindByIdResponse extends OperationResponse {
    public JsonObject object;

    public FindByIdResponse(String message, JsonObject object) {
        super(OperationType.FIND_BY_ID, OperationStatus.OK, message);
        this.object = object;
    }

    public JsonObject getObject() {
        return object;
    }

    public void setObject(JsonObject object) {
        this.object = object;
    }

    @Override
    public int estimatedJsonChars() {
        return withPayloadChars(JsonSizeEstimator.estimate(object));
    }
}
