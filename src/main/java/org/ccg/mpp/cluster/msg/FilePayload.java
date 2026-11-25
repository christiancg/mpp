package org.ccg.mpp.cluster.msg;

import java.util.List;
import org.ccg.mpp.ejson.NullsOmitted;

@NullsOmitted
public class FilePayload {
    private String projectName;
    private String incarnation;
    private List<FileReplicationOp> ops;
    private String summary;
    private boolean summaryMatch;
    private String path;
    private String version;
    private long offset;
    private int length;
    private long size;
    private String content;

    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public String getIncarnation() {
        return incarnation;
    }

    public void setIncarnation(String incarnation) {
        this.incarnation = incarnation;
    }

    public List<FileReplicationOp> getOps() {
        return ops;
    }

    public void setOps(List<FileReplicationOp> ops) {
        this.ops = ops;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public boolean isSummaryMatch() {
        return summaryMatch;
    }

    public void setSummaryMatch(boolean summaryMatch) {
        this.summaryMatch = summaryMatch;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public long getOffset() {
        return offset;
    }

    public void setOffset(long offset) {
        this.offset = offset;
    }

    public int getLength() {
        return length;
    }

    public void setLength(int length) {
        this.length = length;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
