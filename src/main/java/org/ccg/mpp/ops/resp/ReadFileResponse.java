package org.ccg.mpp.ops.resp;

import org.ccg.mpp.data.FileEncoding;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;

public class ReadFileResponse extends OperationResponse {
    private final String path;
    private final String version;
    private final long size;
    private final long offset;
    private final String content;
    private final FileEncoding encoding;
    private final boolean eof;

    public ReadFileResponse(String path, String version, long size, long offset, String content, FileEncoding encoding,
            boolean eof) {
        super(OperationType.READ_FILE, OperationStatus.OK, "File read");
        this.path = path;
        this.version = version;
        this.size = size;
        this.offset = offset;
        this.content = content;
        this.encoding = encoding;
        this.eof = eof;
    }

    @Override
    public int estimatedJsonChars() {
        return withPayloadChars(content == null ? 0L : content.length());
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

    public long getOffset() {
        return offset;
    }

    public String getContent() {
        return content;
    }

    public FileEncoding getEncoding() {
        return encoding;
    }

    public boolean isEof() {
        return eof;
    }
}
