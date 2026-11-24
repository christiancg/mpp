package org.ccg.mpp.ops.resp;

import java.util.List;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.internal.JsonSizeEstimator;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;

public class ListMiddlewaresResponse extends OperationResponse {
    private final List<JsonObject> middlewares;

    public ListMiddlewaresResponse(String message, List<JsonObject> middlewares) {
        super(OperationType.LIST_MIDDLEWARES, OperationStatus.OK, message);
        this.middlewares = middlewares;
    }

    public List<JsonObject> getMiddlewares() {
        return middlewares;
    }

    @Override
    public int estimatedJsonChars() {
        return withPayloadChars(JsonSizeEstimator.estimate(middlewares));
    }
}
