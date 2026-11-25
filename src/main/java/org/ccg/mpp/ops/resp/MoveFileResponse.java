package org.ccg.mpp.ops.resp;

import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;

public class MoveFileResponse extends OperationResponse {
    private final String path;
    private final String version;
    private final int moved;

    public MoveFileResponse(String path, String version, int moved) {
        super(OperationType.MOVE_FILE, OperationStatus.OK, "Moved");
        this.path = path;
        this.version = version;
        this.moved = moved;
    }

    public String getPath() {
        return path;
    }

    public String getVersion() {
        return version;
    }

    public int getMoved() {
        return moved;
    }
}
