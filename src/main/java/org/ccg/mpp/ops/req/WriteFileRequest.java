package org.ccg.mpp.ops.req;

import org.ccg.mpp.data.FileEncoding;
import org.ccg.mpp.data.FileWriteMode;
import org.ccg.mpp.ops.OperationType;

public class WriteFileRequest extends OperationRequest {
    private String path;
    private String content;
    private FileEncoding encoding;
    private FileWriteMode mode;
    private Boolean createParents;
    private String ifVersion;
    private transient byte[] rawContent;
    private transient String unrecognizedOption;

    public WriteFileRequest() {
        super(OperationType.WRITE_FILE, null, null);
    }

    public WriteFileRequest(String projectName) {
        super(OperationType.WRITE_FILE, projectName, null);
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public FileEncoding getEncoding() {
        return encoding;
    }

    public void setEncoding(FileEncoding encoding) {
        this.encoding = encoding;
    }

    public FileWriteMode getMode() {
        return mode;
    }

    public void setMode(FileWriteMode mode) {
        this.mode = mode;
    }

    public Boolean getCreateParents() {
        return createParents;
    }

    public void setCreateParents(Boolean createParents) {
        this.createParents = createParents;
    }

    public String getIfVersion() {
        return ifVersion;
    }

    public void setIfVersion(String ifVersion) {
        this.ifVersion = ifVersion;
    }

    public byte[] getRawContent() {
        return rawContent;
    }

    public void setRawContent(byte[] rawContent) {
        this.rawContent = rawContent;
    }

    public String getUnrecognizedOption() {
        return unrecognizedOption;
    }

    public void setUnrecognizedOption(String unrecognizedOption) {
        this.unrecognizedOption = unrecognizedOption;
    }
}
