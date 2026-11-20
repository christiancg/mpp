package org.ccg.mpp.ops.req;

import java.util.List;
import org.ccg.mpp.ops.OperationType;

public class ReindexRequest extends OperationRequest {
    private List<String> fieldNames;
    private boolean primaryKey;

    public ReindexRequest() {
        super(OperationType.REINDEX, null, null);
    }

    public ReindexRequest(String projectName, String collectionName, List<String> fieldNames) {
        this(projectName, collectionName, fieldNames, false);
    }

    public ReindexRequest(String projectName, String collectionName, List<String> fieldNames, boolean primaryKey) {
        super(OperationType.REINDEX, projectName, collectionName);
        this.fieldNames = fieldNames;
        this.primaryKey = primaryKey;
    }

    public List<String> getFieldNames() {
        return fieldNames != null ? fieldNames : List.of();
    }

    public void setFieldNames(List<String> fieldNames) {
        this.fieldNames = fieldNames;
    }

    public boolean isPrimaryKey() {
        return primaryKey;
    }

    public void setPrimaryKey(boolean primaryKey) {
        this.primaryKey = primaryKey;
    }
}
