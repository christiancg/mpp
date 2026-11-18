package org.ccg.mpp.bckg_ops;

import java.util.concurrent.atomic.AtomicLong;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.ConfigKey;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.fs.CollectionTryLock;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;

public final class IdleLogCheckpoint {
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final Configuration configuration = Configuration.getInstance();
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final AtomicLong nextDue = new AtomicLong();
    private static final CollectionTryLock COLLECTION_WRITE_LOCKS = new CollectionTryLock() {
        @Override
        public boolean tryLock(String dbName, String collName) {
            return locks.tryLockWrite(dbName, collName);
        }

        @Override
        public void unlock(String dbName, String collName) {
            locks.releaseWrite(dbName, collName);
        }
    };

    private static final CollectionTryLock COLLECTION_READ_LOCKS = new CollectionTryLock() {
        @Override
        public boolean tryLock(String dbName, String collName) {
            try {
                return locks.tryLockRead(dbName, collName, Globals.WAL_BACKGROUND_CHECKPOINT_LOCK_WAIT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        @Override
        public void unlock(String dbName, String collName) {
            locks.releaseRead(dbName, collName);
        }
    };

    private IdleLogCheckpoint() {
    }

    public static void runRequested() {
        fs.checkpointRequestedWriteAheadLogs(COLLECTION_READ_LOCKS);
    }

    public static int runDue(long now) {
        final var idleMillis = configuration.longValue(ConfigKey.WAL_IDLE_CHECKPOINT_MS);
        if (idleMillis <= 0L) {
            return 0;
        }
        final var due = nextDue.get();
        if (now < due || !nextDue.compareAndSet(due, now + Math.min(idleMillis, Globals.WAL_IDLE_CHECKPOINT_SCAN_MS))) {
            return 0;
        }
        return fs.checkpointIdleWriteAheadLogs(idleMillis, Globals.WAL_IDLE_CHECKPOINT_MAX_LOGS,
                COLLECTION_WRITE_LOCKS);
    }
}
