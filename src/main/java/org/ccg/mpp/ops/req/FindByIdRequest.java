package org.ccg.mpp.ops.req;

import org.ccg.mpp.ops.OperationType;

public class FindByIdRequest extends OperationRequest {
    private String _id;

    public FindByIdRequest(String projectName, String collectionName) {
        super(OperationType.FIND_BY_ID, projectName, collectionName);
    }

    public String get_id() {
        return _id;
    }

    public void set_id(String _id) {
        this._id = _id;
    }
}
