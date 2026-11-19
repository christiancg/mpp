package org.ccg.mpp.ops;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.ccg.mpp.cluster.ClusterConfig;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.resp.OperationResponse;

public final class OperationLocks {
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private static final Configuration configuration = Configuration.getInstance();

    private OperationLocks() {
    }

    public static OperationResponse withCollectionLock(String projectName, String collName, OperationType type,
            ErrorCode errorCode, OperationResponse.Attempt attempt) {
        return withCollectionLock(projectName, collName, type, errorCode, false, attempt);
    }

    public static OperationResponse withCollectionLock(String projectName, String collName, OperationType type,
            ErrorCode errorCode, boolean bounded, OperationResponse.Attempt attempt) {
        try {
            return OperationResponse.respondOrError(type, errorCode, () -> {
                if (!acquireWriteLock(projectName, collName, bounded)) {
                    return new OperationResponse(type, ErrorCode.TRANSACTION_LOCK_TIMEOUT);
                }
                return attempt.run();
            });
        } finally {
            locks.release(projectName, collName);
        }
    }

    static OperationResponse withGroupedCollectionLock(String projectName, String collName, OperationType type,
            ErrorCode errorCode, GroupCommit.Eligibility eligibility, GroupCommit.GroupedAttempt attempt) {
        if (!GroupCommit.enabled() || !GroupCommit.groupable(eligibility.groupable())) {
            return withCollectionLock(projectName, collName, type, errorCode,
                    () -> attempt.prepare(WriteEffects.IMMEDIATE).commit());
        }
        return GroupCommit.run(projectName, collName,
                new GroupCommit.GroupedWrite(type, errorCode, eligibility, attempt));
    }

    private static boolean acquireWriteLock(String projectName, String collName, boolean bounded)
            throws InterruptedException {
        if (!bounded) {
            locks.lock(projectName, collName);
            return true;
        }
        return locks.tryLockWrite(projectName, collName, clusterConfig.replicationAckTimeoutMs());
    }

    public static long lockBudgetMillis(boolean bounded) {
        return bounded ? clusterConfig.replicationAckTimeoutMs() : configuration.getTransactionLockTimeoutMs();
    }

    public static OperationResponse withProjectShared(String projectName, OperationType type, ErrorCode errorCode,
            OperationResponse.Attempt attempt) {
        final var held = new AtomicBoolean();
        try {
            return OperationResponse.respondOrError(type, errorCode, () -> {
                if (!locks.tryLockProjectShared(projectName, lockBudgetMillis(false))) {
                    return new OperationResponse(type, ErrorCode.TRANSACTION_LOCK_TIMEOUT);
                }
                held.set(true);
                return attempt.run();
            });
        } finally {
            if (held.get()) {
                locks.releaseProjectShared(projectName);
            }
        }
    }

    public static OperationResponse withReadLocks(boolean dirtyRead, List<String> collectionIds, OperationType type,
            ErrorCode errorCode, OperationResponse.Attempt attempt) {
        return withReadLocks(dirtyRead, collectionIds, type, errorCode, false, attempt);
    }

    public static OperationResponse withReadLocks(boolean dirtyRead, List<String> collectionIds, OperationType type,
            ErrorCode errorCode, boolean bounded, OperationResponse.Attempt attempt) {
        return withAcquiredReadLocks(timeout -> locks.acquireReadLocks(dirtyRead, collectionIds, timeout),
                locks::releaseReadLocks, type, errorCode, bounded, collectionIds, attempt);
    }

    public static OperationResponse withPointReadLock(boolean dirtyRead, String collectionId, OperationType type,
            ErrorCode errorCode, boolean bounded, OperationResponse.Attempt attempt) {
        return withAcquiredReadLocks(timeout -> locks.acquirePointReadLock(dirtyRead, collectionId, timeout),
                locks::releasePointReadLocks, type, errorCode, bounded, List.of(collectionId), attempt);
    }

    public static boolean waitsBounded(boolean bounded, List<String> collectionIds) {
        return bounded || locks.fences(collectionIds);
    }

    public static ErrorCode readRefusalOf(boolean bounded, List<String> collectionIds) {
        if (locks.deferredWritesPending(collectionIds)) {
            return ErrorCode.WRITES_NOT_APPLIED;
        }
        if (!bounded && locks.fences(collectionIds)) {
            return ErrorCode.COLLECTION_FENCED;
        }
        return ErrorCode.TRANSACTION_LOCK_TIMEOUT;
    }

    private interface ReadLockAcquisition {
        List<String> acquire(long timeoutMillis) throws InterruptedException;
    }

    private static OperationResponse withAcquiredReadLocks(ReadLockAcquisition acquisition,
            Consumer<List<String>> release, OperationType type, ErrorCode errorCode, boolean bounded,
            List<String> collectionIds, OperationResponse.Attempt attempt) {
        final var readLocks = new ArrayList<String>();
        try {
            return OperationResponse.respondOrError(type, errorCode, () -> {
                final var acquired = acquisition.acquire(
                        waitsBounded(bounded, collectionIds) ? configuration.getTransactionLockTimeoutMs() : 0L);
                if (acquired == null) {
                    return new OperationResponse(type, readRefusalOf(bounded, collectionIds));
                }
                readLocks.addAll(acquired);
                return attempt.run();
            });
        } finally {
            release.accept(readLocks);
        }
    }
}
