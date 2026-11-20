package org.ccg.mpp.cluster.msg;

public class TriggerRunRow {
    private String runId;
    private String status;
    private String project;
    private String collection;
    private String triggerName;
    private String procedureName;
    private String eventType;
    private int attempts;
    private String lastError;
    private long firedAt;
    private long nextAttemptAt;

    @SuppressWarnings("unused")
    public TriggerRunRow() {
    }

    public TriggerRunRow(String runId, String status, String project, String collection, String triggerName,
            String procedureName, String eventType, int attempts, String lastError, long firedAt, long nextAttemptAt) {
        this.runId = runId;
        this.status = status;
        this.project = project;
        this.collection = collection;
        this.triggerName = triggerName;
        this.procedureName = procedureName;
        this.eventType = eventType;
        this.attempts = attempts;
        this.lastError = lastError;
        this.firedAt = firedAt;
        this.nextAttemptAt = nextAttemptAt;
    }

    public String getRunId() {
        return runId;
    }

    public String getStatus() {
        return status;
    }

    public String getProject() {
        return project;
    }

    public String getCollection() {
        return collection;
    }

    public String getTriggerName() {
        return triggerName;
    }

    public String getProcedureName() {
        return procedureName;
    }

    public String getEventType() {
        return eventType;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public long getFiredAt() {
        return firedAt;
    }

    public long getNextAttemptAt() {
        return nextAttemptAt;
    }
}
