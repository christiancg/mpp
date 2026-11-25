package org.ccg.mpp.ops.resp;

import java.util.List;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;

public class ListFilesResponse extends OperationResponse {
    private final List<FileInfo> entries;
    private final String next;

    public ListFilesResponse(List<FileInfo> entries, String next) {
        super(OperationType.LIST_FILES, OperationStatus.OK, "Files listed");
        this.entries = entries;
        this.next = next;
    }

    @Override
    public int estimatedJsonChars() {
        return withPayloadChars((long) entries.size() * FileInfo.ESTIMATED_JSON_CHARS);
    }

    public List<FileInfo> getEntries() {
        return entries;
    }

    public String getNext() {
        return next;
    }
}
