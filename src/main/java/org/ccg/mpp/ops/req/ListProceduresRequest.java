package org.ccg.mpp.ops.req;

import org.ccg.mpp.ops.OperationType;

public class ListProceduresRequest extends OperationRequest {
    private boolean includeSource;

    public ListProceduresRequest() {
        super(OperationType.LIST_PROCEDURES, null, null);
    }

    public ListProceduresRequest(String databaseName) {
        super(OperationType.LIST_PROCEDURES, databaseName, null);
    }

    public boolean isIncludeSource() {
        return includeSource;
    }

    public void setIncludeSource(boolean includeSource) {
        this.includeSource = includeSource;
    }
}
