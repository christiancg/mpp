package org.ccg.mpp.ops.resp;

import java.util.List;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;

public class ReindexResponse extends OperationResponse {
    public List<String> rebuiltFields;

    public ReindexResponse(String message, List<String> rebuiltFields) {
        super(OperationType.REINDEX, OperationStatus.OK, message);
        this.rebuiltFields = rebuiltFields;
    }

    public List<String> getRebuiltFields() {
        return rebuiltFields;
    }

    public void setRebuiltFields(List<String> rebuiltFields) {
        this.rebuiltFields = rebuiltFields;
    }
}
