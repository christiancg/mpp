package org.ccg.mpp.ops.resp;

import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.internal.JsonSizeEstimator;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;

public class GetSchemaResponse extends OperationResponse {
    private final JsonObject schema;

    public GetSchemaResponse(String message, JsonObject schema) {
        super(OperationType.GET_SCHEMA, OperationStatus.OK, message);
        this.schema = schema;
    }

    public JsonObject getSchema() {
        return schema;
    }

    @Override
    public int estimatedJsonChars() {
        return withPayloadChars(JsonSizeEstimator.estimate(schema));
    }
}
