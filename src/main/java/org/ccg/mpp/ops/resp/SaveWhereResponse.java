package org.ccg.mpp.ops.resp;

import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;

public class SaveWhereResponse extends OperationResponse {
    public long updatedCount;

    public SaveWhereResponse(long updatedCount) {
        super(OperationType.SAVE, OperationStatus.OK, "Updated " + updatedCount + " document(s)");
        this.updatedCount = updatedCount;
    }

    public static OperationResponse answering(OperationResponse response) {
        if (response instanceof BulkSaveResponse bulkSaveResponse) {
            return new SaveWhereResponse(bulkSaveResponse.getUpdated().size());
        }
        return response.getType() == OperationType.SAVE ? response : response.as(OperationType.SAVE);
    }

    public long getUpdatedCount() {
        return updatedCount;
    }
}
