package org.ccg.mpp.ops.resp;

import java.util.List;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;

public class SaveSchemaResponse extends OperationResponse {
    private final List<String> warnings;

    public SaveSchemaResponse(String message, List<String> warnings) {
        super(OperationType.SAVE_SCHEMA, OperationStatus.OK, message);
        this.warnings = warnings;
    }

    public List<String> getWarnings() {
        return warnings;
    }
}
