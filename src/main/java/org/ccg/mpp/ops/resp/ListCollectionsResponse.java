package org.ccg.mpp.ops.resp;

import java.util.List;
import org.ccg.mpp.ejson.internal.JsonSizeEstimator;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;

public class ListCollectionsResponse extends OperationResponse {
    private final List<String> collections;

    public ListCollectionsResponse(String message, List<String> collections) {
        super(OperationType.LIST_COLLECTIONS, OperationStatus.OK, message);
        this.collections = collections;
    }

    public List<String> getCollections() {
        return collections;
    }

    @Override
    public int estimatedJsonChars() {
        return withPayloadChars(JsonSizeEstimator.estimateStrings(collections));
    }
}
