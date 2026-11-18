package org.ccg.mpp.ops.resp;

import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.internal.JsonSizeEstimator;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;

public class ServerInfoResponse extends OperationResponse {
    private final JsonObject server;

    public ServerInfoResponse(String message, JsonObject server) {
        super(OperationType.SERVER_INFO, OperationStatus.OK, message);
        this.server = server;
    }

    public JsonObject getServer() {
        return server;
    }

    @Override
    public int estimatedJsonChars() {
        return withPayloadChars(JsonSizeEstimator.estimate(server));
    }
}
