package org.ccg.mpp.fs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.function.LongFunction;
import java.util.function.Predicate;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.fault.FaultInjector;
import org.ccg.mpp.fault.FaultPoint;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.log.Logger;

final class WriteAheadLog {
    private static final Logger logger = Logger.logFor(WriteAheadLog.class);
    private static volatile CheckpointGuard guard = CheckpointGuard.NO_INDEX_WORK;
    private final Configuration configuration = Configuration.getInstance();
    private final FaultInjector faultInjector = IocContainer.get(FaultInjector.class);

    private final FilePaths paths;
    private volatile WalLogPath lastLog;

    WriteAheadLog(FilePaths paths) {
        this.paths = paths;
    }

    private WalLogPath logOf(String projectName, String collName) {
        final var root = paths.dbPath();
        final var last = lastLog;
        if (last != null && last.names(root, projectName, collName)) {
            return last;
        }
        final var file = paths.writeAheadLog(projectName, collName);
        final var made = new WalLogPath(root, projectName, collName, file, file.getAbsolutePath());
        lastLog = made;
        return made;
    }

    private static WalLogState stateOf(WalLogPath log) throws IOException {
        return WalLogState.of(log.file(), log.key(), log.projectName(), log.collName());
    }

    Lock lock(String projectName, String collName) {
        return FileLocks.lockWrite(logOf(projectName, collName).file());
    }

    long append(String projectName, String collName, WalCodec.RecordBuffer record, Collection<Long> pages)
            throws IOException {
        final var log = logOf(projectName, collName);
        var state = stateOf(log);
        if (state.group != null && state.group.failed()) {
            throw new IOException("An earlier write grouped with this one on " + subject(projectName, collName)
                    + " could not be made durable, so the group is refused");
        }
        if (state.knownLength == 0L || ForceFailures.hasFailed(log.file().toPath())) {
            checkpoint(projectName, collName);
            state = stateOf(log);
        }
        if (state.group != null && state.group.idle()) {
            state.group.restartFrom(state.knownLength);
        }
        if (state.group != null && !state.groupBaseLogged) {
            logGroupBase(projectName, collName, log.file(), state);
        }
        final var seq = state.nextSeq;
        record.frame(seq);
        write(log.file(), state, record.bytes(), record.size(), state.group == null);
        state.nextSeq++;
        state.recordsSinceCheckpoint++;
        state.touchedPages.addAll(pages);
        if (state.group != null) {
            state.group.recorded();
        }
        faultInjector.at(FaultPoint.WAL_APPENDED, projectName, collName);
        return seq;
    }

    void abort(String projectName, String collName, long seq) throws IOException {
        final var log = logOf(projectName, collName);
        final var state = stateOf(log);
        final var frame = WalCodec.encodeAbort(state.nextSeq, seq);
        write(log.file(), state, frame, frame.length, state.group == null);
        state.nextSeq++;
        state.recordsSinceCheckpoint++;
    }

    private void logGroupBase(String projectName, String collName, File log, WalLogState state) throws IOException {
        final var pkIndex = paths.pkIndexFile(projectName, collName);
        final var frame = WalCodec.encodeGroupBase(state.nextSeq, pkIndex.exists() ? pkIndex.length() : 0L);
        write(log, state, frame, frame.length, true);
        state.nextSeq++;
        state.groupBaseLogged = true;
        state.group.rebase(state.knownLength);
    }

    void openGroup(String projectName, String collName) throws IOException {
        final var log = logOf(projectName, collName);
        final var lock = FileLocks.lockWrite(log.file());
        try {
            final var state = stateOf(log);
            if (state.group == null) {
                state.group = new WalWriteGroup(state.knownLength);
            }
        } finally {
            lock.unlock();
        }
    }

    boolean closeGroup(String projectName, String collName) throws IOException {
        final var log = logOf(projectName, collName);
        final var pkLock = FileLocks.lockWrite(paths.pkIndexFile(projectName, collName));
        final var walLock = FileLocks.lockWrite(log.file());
        try {
            final var state = stateOf(log);
            if (state.group == null) {
                return true;
            }
            try {
                return settle(projectName, collName, log.file(), state);
            } finally {
                state.endGroup();
            }
        } finally {
            walLock.unlock();
            pkLock.unlock();
        }
    }

    boolean forceGroup(String projectName, String collName) throws IOException {
        final var log = logOf(projectName, collName);
        final var walLock = FileLocks.lockWrite(log.file());
        try {
            final var group = stateOf(log).group;
            if (group == null || group.forced() || !group.failed() && group.idle()) {
                return true;
            }
            return !group.failed() && forceOpenGroup(projectName, collName, log.file(), group);
        } finally {
            walLock.unlock();
        }
    }

    WalWriteGroup openGroupOf(String projectName, String collName) {
        final var state = WalLogState.known(logOf(projectName, collName).key());
        return state == null ? null : state.group;
    }

    private boolean settle(String projectName, String collName, File log, WalLogState state) {
        final var group = state.group;
        if (group.failed()) {
            takeBack(projectName, collName, log, state);
            return false;
        }
        if (group.idle()) {
            return true;
        }
        if (!group.forced() && !forceOpenGroup(projectName, collName, log, group)) {
            takeBack(projectName, collName, log, state);
            return false;
        }
        if (!group.applyBlanks()) {
            state.unapplied = true;
        }
        group.restartFrom(state.knownLength);
        return true;
    }

    private boolean forceOpenGroup(String projectName, String collName, File log, WalWriteGroup group) {
        faultInjector.at(FaultPoint.WAL_GROUP_APPENDED, projectName, collName);
        try {
            group.forceLog(log);
        } catch (IOException e) {
            logger.error("Could not force the write-ahead log of " + subject(projectName, collName) + " for a group of "
                    + group.records() + " write(s); the group is taken back and every one of them fails", e);
            group.fail();
            return false;
        }
        faultInjector.at(FaultPoint.WAL_GROUP_FORCED, projectName, collName);
        group.markForced();
        return true;
    }

    private void takeBack(String projectName, String collName, File log, WalLogState state) {
        final var group = state.group;
        if (group.takeBack(log, state.knownLength, paths.pkIndexFile(projectName, collName))) {
            state.recordsSinceCheckpoint = Math.max(0, state.recordsSinceCheckpoint - group.records());
            state.knownLength = group.logLengthBefore();
        } else {
            state.unapplied = true;
            logger.error("Could not take back a failed group of writes to " + subject(projectName, collName)
                    + "; they are" + " applied before the next write to the collection, or at the next startup");
        }
        group.restartFrom(state.knownLength);
    }

    private static void write(File log, WalLogState state, byte[] frame, int length, boolean force) throws IOException {
        final var at = state.knownLength;
        var written = false;
        try {
            if (state.group == null) {
                try (var channel = WalWriteGroup.openWritable(log)) {
                    state.allocatedLength = WalWriteGroup.writeFrame(channel, log, at, state.allocatedLength, frame,
                            length, force);
                }
            } else {
                state.allocatedLength = state.group.write(log, at, state.allocatedLength, frame, length, force);
            }
            written = true;
        } finally {
            if (!written) {
                if (!WalWriteGroup.erase(log, at, Math.min(at + length, log.length()),
                        "Could not erase a partial append to the write-ahead log " + log.getPath()
                                + "; the next startup cuts it")) {
                    state.reseedOnNextUse();
                }
                state.allocatedLength = Math.max(at, log.length());
            }
        }
        //noinspection NonAtomicOperationOnVolatileField
        state.knownLength += length;
        state.lastWriteMillis = System.currentTimeMillis();
    }

    void markUnapplied(String projectName, String collName) {
        final var state = WalLogState.known(logOf(projectName, collName).key());
        if (state != null) {
            state.unapplied = true;
        }
    }

    boolean requireApplied(String projectName, String collName) throws IOException {
        final var log = logOf(projectName, collName);
        final var known = WalLogState.known(log.key());
        if (known == null || !known.unapplied) {
            DeferredApplies.clear(projectName, collName);
            return false;
        }
        final var pkLock = FileLocks.lockWrite(paths.pkIndexFile(projectName, collName));
        final var walLock = FileLocks.lockWrite(log.file());
        try {
            final var state = stateOf(log);
            if (state.unapplied && !log.file().exists()) {
                state.unapplied = false;
                DeferredApplies.clear(projectName, collName);
                logger.warning("The write-ahead log of " + subject(projectName, collName) + " is gone, so the writes it"
                        + " held can no longer be applied");
            } else if (state.unapplied) {
                if (state.group != null && !settle(projectName, collName, log.file(), state)) {
                    throw new IOException("Could not make the open write group of " + subject(projectName, collName)
                            + " durable before applying its logged writes");
                }
                WalWriteGroup.forceUnheld(log.file());
                DeferredApplies.replay(paths, projectName, collName,
                        WalCodec.decodeAll(Files.readAllBytes(log.file().toPath())));
                state.unapplied = false;
                DeferredApplies.clear(projectName, collName);
                logger.warning("Applied the logged writes of " + subject(projectName, collName)
                        + " that an earlier write" + " could not");
                return true;
            }
            return false;
        } finally {
            walLock.unlock();
            pkLock.unlock();
        }
    }

    void settleBeforeRewrite(String projectName, String collName) throws IOException {
        final var log = logOf(projectName, collName);
        final var pkLock = FileLocks.lockWrite(paths.pkIndexFile(projectName, collName));
        final var walLock = FileLocks.lockWrite(log.file());
        try {
            requireApplied(projectName, collName);
            final var state = stateOf(log);
            if (state.knownLength == 0L || ForceFailures.hasFailed(log.file().toPath())) {
                checkpoint(projectName, collName);
            }
            if (state.group != null) {
                if (!settle(projectName, collName, log.file(), state)) {
                    throw new IOException("Could not force the open write group of " + subject(projectName, collName)
                            + " before rewriting one of its pages; its writes were taken back");
                }
                state.endGroup();
            }
            requireApplied(projectName, collName);
            FailedForceRebuild.rebuildFailed(paths, projectName, collName, log.file());
        } finally {
            walLock.unlock();
            pkLock.unlock();
        }
    }

    long beginRewrite(String projectName, String collName, long page, long lengthBefore, ForceGroup alongside)
            throws IOException {
        alongside.add(paths.collectionPage(projectName, collName, page).toPath());
        alongside.force();
        return appendFrame(projectName, collName, seq -> WalCodec.encodeRewriteBegin(seq, page, lengthBefore), true,
                page);
    }

    void rewriteLanded(String projectName, String collName, long page) {
        final var state = WalLogState.known(logOf(projectName, collName).key());
        if (state != null) {
            state.openRewrites.remove(page);
        }
    }

    void endRewrite(String projectName, String collName, long beginSeq, long page, long lengthAfter, boolean force)
            throws IOException {
        appendFrame(projectName, collName, seq -> WalCodec.encodeRewriteEnd(seq, beginSeq, page, lengthAfter), force,
                null);
    }

    private long appendFrame(String projectName, String collName, LongFunction<byte[]> frame, boolean force,
            Long openedRewrite) throws IOException {
        final var log = logOf(projectName, collName);
        final var walLock = FileLocks.lockWrite(log.file());
        try {
            final var state = stateOf(log);
            final var seq = state.nextSeq;
            final var bytes = frame.apply(seq);
            write(log.file(), state, bytes, bytes.length, force);
            state.nextSeq++;
            state.recordsSinceCheckpoint++;
            if (openedRewrite != null) {
                state.openRewrites.add(openedRewrite);
            }
            return seq;
        } finally {
            walLock.unlock();
        }
    }

    void track(String projectName, String collName) throws IOException {
        final var log = logOf(projectName, collName);
        final var lock = FileLocks.lockWrite(log.file());
        try {
            stateOf(log);
        } finally {
            lock.unlock();
        }
    }

    static void guardWith(CheckpointGuard checkpointGuard) {
        guard = checkpointGuard;
    }

    void checkpointIfFull(String projectName, String collName) {
        final var log = logOf(projectName, collName);
        final var state = WalLogState.known(log.key());
        final var limit = configuration.getWalCheckpointBytes();
        if (state == null || state.lengthBeyondPending() <= limit) {
            return;
        }
        if (state.lengthBeyondPending() / Globals.WAL_SYNC_CHECKPOINT_FACTOR <= limit) {
            WalCheckpointRequests.request(log.key(), projectName, collName);
            return;
        }
        try {
            checkpoint(projectName, collName);
        } catch (IOException e) {
            logger.error("Could not checkpoint the full write-ahead log of " + subject(projectName, collName)
                    + "; it keeps its records and the next write or the shutdown retries", e);
        }
    }

    void checkpoint(String projectName, String collName) throws IOException {
        if (!paths.collectionFolder(projectName, collName).isDirectory()) {
            return;
        }
        final var log = logOf(projectName, collName);
        final var pkIndex = paths.pkIndexFile(projectName, collName);
        final var pkLock = FileLocks.lockWrite(pkIndex);
        final var walLock = FileLocks.lockWrite(log.file());
        try {
            requireApplied(projectName, collName);
            final var state = stateOf(log);
            state.refuseWhileRewriting(subject(projectName, collName));
            if (state.group != null) {
                final var carriedWrites = !state.group.idle();
                if (!settle(projectName, collName, log.file(), state)) {
                    throw new IOException("Could not force the open write group of " + subject(projectName, collName)
                            + " before a checkpoint; its writes were taken back");
                }
                if (carriedWrites) {
                    state.endGroup();
                }
            }
            FailedForceRebuild.rebuildFailed(paths, projectName, collName, log.file());
            if (!isDirty(state)) {
                return;
            }
            final var pending = List.copyOf(guard.pendingIds(projectName, collName));
            final var group = new ForceGroup();
            for (final var page : state.touchedPages) {
                final var file = paths.collectionPage(projectName, collName, page);
                if (file.exists()) {
                    group.add(file.toPath());
                }
            }
            if (pkIndex.exists()) {
                group.add(pkIndex.toPath());
            }
            FieldIndexLog.forceUnforced(paths.collectionFolder(projectName, collName), group);
            group.force();
            faultInjector.at(FaultPoint.WAL_CHECKPOINT_FORCED, projectName, collName);
            final var checkpointFrame = WalCodec.encode(
                    new WalCheckpoint(state.nextSeq, projectName, collName, pkIndex.exists() ? pkIndex.length() : 0L,
                            DocumentPageStore.pageFileLengths(paths, projectName, collName)));
            final var bytes = WalCodec.withPending(checkpointFrame, state.nextSeq + 1, pending);
            FileLocks.rewriteFileAtomically(log.file().toPath(), bytes);
            state.nextSeq += pending.isEmpty() ? 1 : 2;
            state.knownLength = bytes.length;
            state.allocatedLength = bytes.length;
            state.recordsSinceCheckpoint = 0;
            state.groupBaseLogged = false;
            state.pendingLogged = !pending.isEmpty();
            state.pendingFrameBytes = bytes.length - checkpointFrame.length;
            state.touchedPages.clear();
            if (state.group != null) {
                state.group.restartFrom(state.knownLength);
            }
        } finally {
            walLock.unlock();
            pkLock.unlock();
        }
    }

    record WalLogStatus(String projectName, String collName, boolean dirty, boolean grouped, long lastWriteMillis) {
    }

    List<WalLogStatus> statusesUnder(String root) {
        final var statuses = new ArrayList<WalLogStatus>();
        for (final var state : WalLogState.all()) {
            if (state.path.startsWith(root)) {
                statuses.add(new WalLogStatus(state.projectName, state.collName, isDirty(state), state.group != null,
                        state.lastWriteMillis));
            }
        }
        return statuses;
    }

    private static boolean isDirty(WalLogState state) {
        return state.recordsSinceCheckpoint > 0 || state.groupBaseLogged || state.knownLength == 0L
                || state.pendingLogged && !guard.holdsIndexWork(state.projectName, state.collName)
                || ForceFailures.hasFailed(Path.of(state.path));
    }

    static boolean holdsIndexWork(String projectName, String collName) {
        return guard.holdsIndexWork(projectName, collName);
    }

    boolean checkpointIfIdle(String projectName, String collName, long idleMillis) throws IOException {
        return checkpointWhen(projectName, collName, state -> state.group == null && isDirty(state)
                && System.currentTimeMillis() - state.lastWriteMillis >= idleMillis);
    }

    boolean checkpointRequested(String projectName, String collName) throws IOException {
        return checkpointWhen(projectName, collName, state -> state.group == null && !state.unapplied
                && state.lengthBeyondPending() > configuration.getWalCheckpointBytes());
    }

    private boolean checkpointWhen(String projectName, String collName, Predicate<WalLogState> due) throws IOException {
        final var log = logOf(projectName, collName);
        final var pkLock = FileLocks.lockWrite(paths.pkIndexFile(projectName, collName));
        final var walLock = FileLocks.lockWrite(log.file());
        try {
            final var state = WalLogState.known(log.key());
            if (state == null || !due.test(state)) {
                return false;
            }
            checkpoint(projectName, collName);
            return true;
        } finally {
            walLock.unlock();
            pkLock.unlock();
        }
    }

    static void forgetUnder(File folder) {
        WalLogState.forgetUnder(folder);
        WalCheckpointRequests.forgetUnder(folder);
        ForceFailures.forgetUnder(folder);
    }

    static String subject(String projectName, String collName) {
        return projectName + Globals.COLL_IDENTIFIER_SEPARATOR + collName;
    }
}
