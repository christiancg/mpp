package org.ccg.mpp.ops.resp;

import java.util.List;
import org.ccg.mpp.ejson.internal.JsonSizeEstimator;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;

public class ListDatabasesResponse extends OperationResponse {
    private final List<String> databases;

    public ListDatabasesResponse(String message, List<String> databases) {
        super(OperationType.LIST_DATABASES, OperationStatus.OK, message);
        this.databases = databases;
    }

    public List<String> getDatabases() {
        return databases;
    }

    @Override
    public int estimatedJsonChars() {
        return withPayloadChars(JsonSizeEstimator.estimateStrings(databases));
    }
}
