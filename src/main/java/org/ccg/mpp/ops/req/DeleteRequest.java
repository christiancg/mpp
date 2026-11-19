package org.ccg.mpp.ops.req;

import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.req.agg.BaseOperator;

public class DeleteRequest extends OperationRequest {
    public String _id;
    public BaseOperator condition;

    public DeleteRequest(String projectName, String collectionName) {
        super(OperationType.DELETE, projectName, collectionName);
    }

    public String get_id() {
        return _id;
    }

    public void set_id(String _id) {
        this._id = _id;
    }

    public BaseOperator getCondition() {
        return condition;
    }

    public void setCondition(BaseOperator condition) {
        this.condition = condition;
    }
}
