package org.ccg.mpp.ops.req;

import org.ccg.mpp.ops.OperationType;

public class ListCollectionsRequest extends OperationRequest {
    public ListCollectionsRequest(String projectName) {
        super(OperationType.LIST_COLLECTIONS, projectName, null);
    }
}
