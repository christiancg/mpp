package org.ccg.mpp.cluster.msg;

public class RunningScript {
    private String runId;
    private String kind;
    private String project;
    private String name;
    private String username;
    private long startedAt;

    @SuppressWarnings("unused")
    public RunningScript() {
    }

    public RunningScript(String runId, String kind, String project, String name, String username, long startedAt) {
        this.runId = runId;
        this.kind = kind;
        this.project = project;
        this.name = name;
        this.username = username;
        this.startedAt = startedAt;
    }

    public String getRunId() {
        return runId;
    }

    public String getKind() {
        return kind;
    }

    public String getProject() {
        return project;
    }

    public String getName() {
        return name;
    }

    public String getUsername() {
        return username;
    }

    public long getStartedAt() {
        return startedAt;
    }
}
