package org.ccg.mpp.ops.resp;

import java.util.List;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.internal.JsonSizeEstimator;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;

public class ListProceduresResponse extends OperationResponse {
    private final List<JsonObject> procedures;

    public ListProceduresResponse(String message, List<JsonObject> procedures) {
        super(OperationType.LIST_PROCEDURES, OperationStatus.OK, message);
        this.procedures = procedures;
    }

    public List<JsonObject> getProcedures() {
        return procedures;
    }

    @Override
    public int estimatedJsonChars() {
        return withPayloadChars(JsonSizeEstimator.estimate(procedures));
    }
}
