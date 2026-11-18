package org.ccg.mpp.ops.resp;

import java.util.List;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.internal.JsonSizeEstimator;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;

public class ListUsersResponse extends OperationResponse {
    private final List<JsonObject> users;

    public ListUsersResponse(String message, List<JsonObject> users) {
        super(OperationType.LIST_USERS, OperationStatus.OK, message);
        this.users = users;
    }

    public List<JsonObject> getUsers() {
        return users;
    }

    @Override
    public int estimatedJsonChars() {
        return withPayloadChars(JsonSizeEstimator.estimate(users));
    }
}
