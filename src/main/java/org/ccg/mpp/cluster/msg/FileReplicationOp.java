package org.ccg.mpp.cluster.msg;

import org.ccg.mpp.ejson.NullsOmitted;

@NullsOmitted
public class FileReplicationOp {
    private String type;
    private String path;
    private String target;
    private String version;
    private long size;
    private String baseVersion;
    private long baseSize;
    private String content;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public String getBaseVersion() {
        return baseVersion;
    }

    public void setBaseVersion(String baseVersion) {
        this.baseVersion = baseVersion;
    }

    public long getBaseSize() {
        return baseSize;
    }

    public void setBaseSize(long baseSize) {
        this.baseSize = baseSize;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
