package org.ccg.mpp.fs;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.log.Logger;

final class WalCheckpoints {
    private static final Logger logger = Logger.logFor(WalCheckpoints.class);

    private final FilePaths paths;
    private final WriteAheadLog writeAheadLog;

    WalCheckpoints(FilePaths paths, WriteAheadLog writeAheadLog) {
        this.paths = paths;
        this.writeAheadLog = writeAheadLog;
    }

    @SuppressWarnings({"resource", "ResultOfMethodCallIgnored"})
    void atShutdown(long deadline) {
        final var dirty = new ArrayList<WriteAheadLog.WalLogStatus>();
        for (final var status : writeAheadLog.statusesUnder(root())) {
            if (status.dirty() && !replayedAtStartup(status.projectName())) {
                dirty.add(status);
            }
        }
        if (dirty.isEmpty()) {
            return;
        }
        final var next = new AtomicInteger();
        final var finished = new AtomicInteger();
        final var workers = Math.min(Globals.WAL_CHECKPOINT_PARALLELISM, dirty.size());
        final var executor = Executors.newVirtualThreadPerTaskExecutor();
        for (var worker = 0; worker < workers; worker++) {
            executor.execute(() -> checkpointUntil(dirty, next, finished, deadline));
        }
        executor.shutdown();
        try {
            executor.awaitTermination(Math.max(0L, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        final var left = dirty.size() - finished.get();
        if (left > 0) {
            logger.warning("The shutdown budget ran out with " + left + " write-ahead log(s) not checkpointed; the"
                    + " next startup replays them");
        }
    }

    private void checkpointUntil(List<WriteAheadLog.WalLogStatus> logs, AtomicInteger next, AtomicInteger finished,
            long deadline) {
        while (System.currentTimeMillis() < deadline) {
            final var index = next.getAndIncrement();
            if (index >= logs.size()) {
                return;
            }
            checkpointAtShutdown(logs.get(index));
            finished.incrementAndGet();
        }
    }

    private void checkpointAtShutdown(WriteAheadLog.WalLogStatus status) {
        final var subject = WriteAheadLog.subject(status.projectName(), status.collName());
        if (WriteAheadLog.holdsIndexWork(status.projectName(), status.collName())) {
            logger.info("Leaving the write-ahead log of " + subject + " as it is: its field-index work is still"
                    + " pending, so the next startup replays it and finishes that work");
            return;
        }
        try {
            writeAheadLog.checkpoint(status.projectName(), status.collName());
        } catch (IOException e) {
            logger.error("Could not checkpoint the write-ahead log of " + subject + "; the next startup replays it", e);
        }
    }

    int idle(long idleMillis, int maxLogs, CollectionTryLock collectionLocks) {
        final var now = System.currentTimeMillis();
        var checkpointed = 0;
        for (final var status : writeAheadLog.statusesUnder(root())) {
            if (checkpointed >= maxLogs) {
                break;
            }
            if (!status.dirty() || status.grouped() || now - status.lastWriteMillis() < idleMillis
                    || WriteAheadLog.holdsIndexWork(status.projectName(), status.collName())
                    || CheckpointBackoff.waiting(stateOf(status.projectName(), status.collName()), now)
                    || !collectionLocks.tryLock(status.projectName(), status.collName())) {
                continue;
            }
            try {
                if (writeAheadLog.checkpointIfIdle(status.projectName(), status.collName(), idleMillis)) {
                    checkpointed++;
                    succeeded(status.projectName(), status.collName());
                }
            } catch (IOException e) {
                failed(status.projectName(), status.collName(), e, "the idle write-ahead log",
                        "it keeps its records and a later pass retries");
            } finally {
                collectionLocks.unlock(status.projectName(), status.collName());
            }
        }
        return checkpointed;
    }

    int requested(CollectionTryLock collectionLocks) {
        var checkpointed = 0;
        for (final var request : WalCheckpointRequests.takeAll()) {
            final var projectName = request.projectName();
            final var collName = request.collName();
            if (!paths.collectionFolder(projectName, collName).isDirectory()
                    || CheckpointBackoff.waiting(stateOf(projectName, collName), System.currentTimeMillis())
                    || !collectionLocks.tryLock(projectName, collName)) {
                continue;
            }
            try {
                if (writeAheadLog.checkpointRequested(projectName, collName)) {
                    checkpointed++;
                    succeeded(projectName, collName);
                }
            } catch (IOException e) {
                failed(projectName, collName, e, "the full write-ahead log",
                        "it keeps its records and the next write past its size retries");
            } finally {
                collectionLocks.unlock(projectName, collName);
            }
        }
        return checkpointed;
    }

    private WalLogState stateOf(String projectName, String collName) {
        return WalLogState.known(paths.writeAheadLog(projectName, collName).getAbsolutePath());
    }

    private void succeeded(String projectName, String collName) {
        final var streak = CheckpointBackoff.succeeded(stateOf(projectName, collName));
        if (streak > 0) {
            logger.info("Checkpointed the write-ahead log of " + WriteAheadLog.subject(projectName, collName)
                    + " after " + streak + " failed attempt(s)");
        }
    }

    private void failed(String projectName, String collName, IOException failure, String what, String consequence) {
        final var state = stateOf(projectName, collName);
        final var delay = CheckpointBackoff.failed(state, System.currentTimeMillis());
        final var subject = WriteAheadLog.subject(projectName, collName);
        if (state == null || state.checkpointFailures == 1) {
            logger.error("Could not checkpoint " + what + " of " + subject + " in the background; " + consequence,
                    failure);
            return;
        }
        logger.warning("Still cannot checkpoint " + what + " of " + subject + " after " + state.checkpointFailures
                + " attempts; next try in " + delay / 1000 + " s: " + failure.getMessage());
    }

    static boolean replayedAtStartup(String projectName) {
        return Globals.ADMIN_PAGES_PROJECT_NAME.equals(projectName);
    }

    private String root() {
        return new File(paths.dbPath()).getAbsolutePath() + File.separator;
    }
}
