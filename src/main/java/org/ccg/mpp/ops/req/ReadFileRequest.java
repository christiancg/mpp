package org.ccg.mpp.ops.req;

import org.ccg.mpp.data.FileEncoding;
import org.ccg.mpp.ops.OperationType;

public class ReadFileRequest extends OperationRequest {
    private String path;
    private long offset;
    private Long length;
    private FileEncoding encoding;
    private transient String unrecognizedOption;

    public ReadFileRequest() {
        super(OperationType.READ_FILE, null, null);
    }

    public ReadFileRequest(String projectName) {
        super(OperationType.READ_FILE, projectName, null);
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public long getOffset() {
        return offset;
    }

    public void setOffset(long offset) {
        this.offset = offset;
    }

    public Long getLength() {
        return length;
    }

    public void setLength(Long length) {
        this.length = length;
    }

    public FileEncoding getEncoding() {
        return encoding;
    }

    public void setEncoding(FileEncoding encoding) {
        this.encoding = encoding;
    }

    public String getUnrecognizedOption() {
        return unrecognizedOption;
    }

    public void setUnrecognizedOption(String unrecognizedOption) {
        this.unrecognizedOption = unrecognizedOption;
    }
}
