package org.ccg.mpp.ops.req;

import org.ccg.mpp.ops.OperationType;

public class DropIndexRequest extends OperationRequest {
    private String fieldName;

    public DropIndexRequest(String databaseName, String collectionName, String fieldName) {
        super(OperationType.DROP_INDEX, databaseName, collectionName);
        this.fieldName = fieldName;
    }

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }
}
