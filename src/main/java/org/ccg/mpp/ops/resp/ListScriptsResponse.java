package org.ccg.mpp.ops.resp;

import java.util.List;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.internal.JsonSizeEstimator;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;

public class ListScriptsResponse extends OperationResponse {
    private final List<JsonObject> scripts;

    public ListScriptsResponse(String message, List<JsonObject> scripts) {
        super(OperationType.LIST_SCRIPTS, OperationStatus.OK, message);
        this.scripts = scripts;
    }

    public List<JsonObject> getScripts() {
        return scripts;
    }

    @Override
    public int estimatedJsonChars() {
        return withPayloadChars(JsonSizeEstimator.estimate(scripts));
    }
}
