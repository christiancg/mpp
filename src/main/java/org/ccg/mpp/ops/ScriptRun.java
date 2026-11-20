package org.ccg.mpp.ops;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

public final class ScriptRun {
    private final String runId = UUID.randomUUID().toString();
    private final ScriptRunKind kind;
    private final String project;
    private final String name;
    private final String username;
    private final UUID clientId;
    private final long startedAt = System.currentTimeMillis();
    private final Thread thread = Thread.currentThread();
    private final AtomicBoolean cancelled = new AtomicBoolean();

    ScriptRun(ScriptRunKind kind, String project, String name, String username, UUID clientId) {
        this.kind = kind;
        this.project = project;
        this.name = name;
        this.username = username;
        this.clientId = clientId;
    }

    public String runId() {
        return runId;
    }

    public ScriptRunKind kind() {
        return kind;
    }

    public String project() {
        return project;
    }

    public String name() {
        return name;
    }

    public String username() {
        return username;
    }

    public UUID clientId() {
        return clientId;
    }

    public long startedAt() {
        return startedAt;
    }

    public Thread thread() {
        return thread;
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void cancel() {
        cancelled.set(true);
        LockSupport.unpark(thread);
    }
}
