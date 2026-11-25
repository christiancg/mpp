package org.ccg.mpp.ops.resp;

import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;

public class StatFileResponse extends OperationResponse {
    private final String path;
    private final String kind;
    private final long size;
    private final String version;
    private final long modifiedAt;

    public StatFileResponse(FileInfo info) {
        super(OperationType.STAT_FILE, OperationStatus.OK, "File found");
        this.path = info.getPath();
        this.kind = info.getKind();
        this.size = info.getSize();
        this.version = info.getVersion();
        this.modifiedAt = info.getModifiedAt();
    }

    @Override
    public int estimatedJsonChars() {
        return withPayloadChars(FileInfo.ESTIMATED_JSON_CHARS);
    }

    public String getPath() {
        return path;
    }

    public String getKind() {
        return kind;
    }

    public long getSize() {
        return size;
    }

    public String getVersion() {
        return version;
    }

    public long getModifiedAt() {
        return modifiedAt;
    }
}
