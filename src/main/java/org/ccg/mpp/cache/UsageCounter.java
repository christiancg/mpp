package org.ccg.mpp.cache;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class UsageCounter {
    private final AccessKind kind;
    private final String projectName;
    private final String collName;
    private final String indexKey;
    private final AtomicLong accessCount;
    private final AtomicLong lastAccessMillis;
    private final AtomicBoolean unflushed = new AtomicBoolean();

    public UsageCounter(AccessKind kind, String projectName, String collName, String indexKey, long initialAccessCount,
            long initialLastAccessMillis) {
        this.kind = kind;
        this.projectName = projectName;
        this.collName = collName;
        this.indexKey = indexKey == null ? "" : indexKey;
        this.accessCount = new AtomicLong(initialAccessCount);
        this.lastAccessMillis = new AtomicLong(initialLastAccessMillis);
    }

    public void increment(long timestampMillis) {
        accessCount.incrementAndGet();
        lastAccessMillis.set(timestampMillis);
    }

    boolean markUnflushed() {
        return unflushed.compareAndSet(false, true);
    }

    void clearUnflushed() {
        unflushed.set(false);
    }

    public AccessKind kind() {
        return kind;
    }

    public String projectName() {
        return projectName;
    }

    public String collName() {
        return collName;
    }

    public String indexKey() {
        return indexKey;
    }

    public long getAccessCount() {
        return accessCount.get();
    }

    public long getLastAccessMillis() {
        return lastAccessMillis.get();
    }
}
