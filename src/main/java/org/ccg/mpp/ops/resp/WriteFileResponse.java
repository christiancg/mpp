package org.ccg.mpp.ops.resp;

import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;

public class WriteFileResponse extends OperationResponse {
    private final String path;
    private final String version;
    private final long size;

    public WriteFileResponse(String path, String version, long size) {
        super(OperationType.WRITE_FILE, OperationStatus.OK, "File written");
        this.path = path;
        this.version = version;
        this.size = size;
    }

    public String getPath() {
        return path;
    }

    public String getVersion() {
        return version;
    }

    public long getSize() {
        return size;
    }
}
