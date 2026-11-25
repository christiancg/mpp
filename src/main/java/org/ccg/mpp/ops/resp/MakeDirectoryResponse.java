package org.ccg.mpp.ops.resp;

import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;

public class MakeDirectoryResponse extends OperationResponse {
    private final String path;
    private final String version;
    private final int created;

    public MakeDirectoryResponse(String path, String version, int created) {
        super(OperationType.MAKE_DIRECTORY, OperationStatus.OK, "Folder created");
        this.path = path;
        this.version = version;
        this.created = created;
    }

    public String getPath() {
        return path;
    }

    public String getVersion() {
        return version;
    }

    public int getCreated() {
        return created;
    }
}
