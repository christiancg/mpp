package org.ccg.mpp.test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.ioc.IocContainer;

public final class IndexRaceSupport {
    private static final int READS = 500;
    private static final long PAUSE_BETWEEN_CHURNS_NANOS = 100_000L;
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ResourceLocking rl = IocContainer.get(ResourceLocking.class);

    public interface Read {
        void run() throws Exception;
    }

    private IndexRaceSupport() {
    }

    public static <T> void readWhileTheIndexerChurns(String projectName, String collName, String fieldName,
            Class<T> indexType, Read read) throws Exception {
        shareTheCachedIndex(projectName, collName, fieldName, indexType);
        final var stop = new AtomicBoolean();
        final var writerFailure = new AtomicReference<Throwable>();
        final var writer = new Thread(() -> churn(projectName, collName, fieldName, indexType, stop, writerFailure),
                "index-churn");
        writer.start();
        try {
            for (var i = 0; i < READS; i++) {
                read.run();
            }
        } finally {
            stop.set(true);
            writer.join(5_000L);
        }
        if (writerFailure.get() != null) {
            throw new AssertionError("the background indexer failed", writerFailure.get());
        }
    }

    private static <T> void shareTheCachedIndex(String projectName, String collName, String fieldName,
            Class<T> indexType) throws Exception {
        rl.lockRead(projectName, collName);
        try {
            cache.getFieldIndexAndLoadIfNecessary(projectName, collName, fieldName, indexType);
        } finally {
            rl.releaseRead(projectName, collName);
        }
    }

    private static <T> void churn(String projectName, String collName, String fieldName, Class<T> indexType,
            AtomicBoolean stop, AtomicReference<Throwable> failure) {
        var generation = 0;
        while (!stop.get()) {
            try {
                rl.lockIndex(projectName, collName, fieldName);
                try {
                    final var entries = cache.getFieldIndexAndLoadIfNecessary(projectName, collName, fieldName,
                            indexType);
                    if (entries != null) {
                        for (final var entry : entries) {
                            entry.getIds().add("churn-" + generation);
                            entry.getIds().remove("churn-" + (generation - 1));
                        }
                        generation++;
                    }
                } finally {
                    rl.releaseIndex(projectName, collName, fieldName);
                }
                LockSupport.parkNanos(PAUSE_BETWEEN_CHURNS_NANOS);
            } catch (Exception e) {
                failure.compareAndSet(null, e);
                return;
            }
        }
    }
}
