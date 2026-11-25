package org.ccg.mpp.ops.resp;

@SuppressWarnings("ClassCanBeRecord")
public class FileInfo {
    static final int ESTIMATED_JSON_CHARS = 160;
    private final String path;
    private final String kind;
    private final long size;
    private final String version;
    private final long modifiedAt;

    public FileInfo(String path, String kind, long size, String version, long modifiedAt) {
        this.path = path;
        this.kind = kind;
        this.size = size;
        this.version = version;
        this.modifiedAt = modifiedAt;
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
