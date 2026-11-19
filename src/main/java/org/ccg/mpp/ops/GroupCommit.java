package org.ccg.mpp.ops;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import org.ccg.mpp.bckg_ops.events.EventType;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.log.Logger;
import org.ccg.mpp.ops.req.DeleteRequest;
import org.ccg.mpp.ops.req.SaveRequest;
import org.ccg.mpp.ops.resp.OperationResponse;

final class GroupCommit {
    static final int MAX_GROUP_OPS = 64;
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final Configuration configuration = Configuration.getInstance();
    private static final Map<String, GroupQueue> queues = new ConcurrentHashMap<>();
    private static final Logger logger = Logger.logFor(GroupCommit.class);

    private enum Acquired {
        HELD, HELD_AFTER_INTERRUPT, WITHDRAWN
    }

    record Eligibility(BooleanSupplier groupable, String id, BooleanSupplier sideEffects, boolean checksExistence) {
    }

    @FunctionalInterface
    interface GroupedAttempt {
        GroupedCommit prepare(WriteEffects effects) throws Exception;
    }

    @FunctionalInterface
    interface GroupedCommit {
        OperationResponse commit() throws Exception;
    }

    static GroupedCommit answered(OperationResponse response) {
        return () -> response;
    }

    static final class GroupedWrite {
        private final OperationType type;
        private final ErrorCode errorCode;
        private final String id;
        private final BooleanSupplier sideEffects;
        private final boolean checksExistence;
        private final GroupedAttempt attempt;
        private OperationResponse response;
        private volatile boolean served;
        private Boolean sideEffecting;

        GroupedWrite(OperationType type, ErrorCode errorCode, Eligibility eligibility, GroupedAttempt attempt) {
            this.type = type;
            this.errorCode = errorCode;
            this.id = eligibility.id();
            this.sideEffects = eligibility.sideEffects();
            this.checksExistence = eligibility.checksExistence();
            this.attempt = attempt;
        }

        OperationResponse runAlone() {
            return OperationResponse.respondOrError(type, errorCode,
                    () -> attempt.prepare(WriteEffects.IMMEDIATE).commit());
        }

        GroupedCommit prepare(WriteEffects effects) {
            try {
                return attempt.prepare(effects);
            } catch (Exception e) {
                return answered(OperationResponse.respondOrError(type, errorCode, () -> {
                    throw e;
                }));
            }
        }

        OperationResponse commit(GroupedCommit commit) {
            return OperationResponse.respondOrError(type, errorCode, commit::commit);
        }

        void serve(OperationResponse result) {
            response = result;
            served = true;
        }

        OperationResponse failure() {
            return new OperationResponse(type, errorCode);
        }

        OperationResponse unknownOutcome() {
            return new OperationResponse(type, ErrorCode.WRITE_LOGGED_NOT_APPLIED);
        }

        private boolean sideEffecting() {
            if (sideEffecting == null) {
                sideEffecting = GroupCommit.sideEffecting(sideEffects);
            }
            return sideEffecting;
        }

        private boolean sequencedById() {
            return checksExistence || sideEffecting();
        }
    }

    private static final class GroupQueue {
        private final Queue<GroupedWrite> writes = new ConcurrentLinkedQueue<>();
        private final ReentrantLock monitor = new ReentrantLock();
        private final Condition turn = monitor.newCondition();
        private boolean leading;
    }

    private GroupCommit() {
    }

    static boolean enabled() {
        return configuration.isSyncWrites();
    }

    static boolean groupable(BooleanSupplier check) {
        try {
            return check.getAsBoolean();
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    static boolean sideEffecting(BooleanSupplier check) {
        try {
            return check.getAsBoolean();
        } catch (RuntimeException unreadable) {
            return true;
        }
    }

    static Eligibility forSave(SaveRequest request) {
        final var projectName = request.getProjectName();
        final var collName = request.getCollectionName();
        final var depth = request.getTriggerDepth();
        final var object = request.getObject();
        final var objectId = object != null && object.has(Globals.PK_FIELD)
                && object.get(Globals.PK_FIELD).isJsonString()
                        ? object.get(Globals.PK_FIELD).asJsonString().getValue()
                        : null;
        return new Eligibility(() -> depth == 0 && !request.isBackgroundFollowOn(),
                request.get_id() != null ? request.get_id() : objectId,
                () -> TriggerHelper.firesOnSave(projectName, collName, depth)
                        || BeforeHookContext.hasHooksFor(projectName, collName, EventType.CREATED)
                        || BeforeHookContext.hasHooksFor(projectName, collName, EventType.UPDATED),
                request.isFailOnUpdate());
    }

    static Eligibility forDelete(DeleteRequest request) {
        final var projectName = request.getProjectName();
        final var collName = request.getCollectionName();
        final var depth = request.getTriggerDepth();
        return new Eligibility(() -> depth == 0, request.get_id(),
                () -> TriggerHelper.firesOnDelete(projectName, collName, depth)
                        || BeforeHookContext.hasHooksFor(projectName, collName, EventType.DELETED),
                false);
    }

    static OperationResponse run(String projectName, String collName, GroupedWrite write) {
        final var queue = queueOf(projectName, collName);
        queue.writes.add(write);
        var interrupted = false;
        queue.monitor.lock();
        try {
            while (!write.served && queue.leading) {
                try {
                    queue.turn.await();
                } catch (InterruptedException e) {
                    if (queue.writes.remove(write)) {
                        Thread.currentThread().interrupt();
                        return write.failure();
                    }
                    interrupted = true;
                }
            }
            if (write.served) {
                restoreInterrupt(interrupted);
                return write.response;
            }
            queue.leading = true;
        } finally {
            queue.monitor.unlock();
        }
        try {
            return lead(projectName, collName, queue, write, interrupted);
        } finally {
            passTurn(queue);
        }
    }

    static int queuedFor(String projectName, String collName) {
        return queueOf(projectName, collName).writes.size();
    }

    static void forget(String projectName, String collName) {
        queues.remove(Cache.getCollectionIdentifier(projectName, collName));
    }

    static void forgetProject(String projectName) {
        final var prefix = projectName + Globals.COLL_IDENTIFIER_SEPARATOR;
        queues.keySet().removeIf(collId -> collId.startsWith(prefix));
    }

    private static GroupQueue queueOf(String projectName, String collName) {
        return queues.computeIfAbsent(Cache.getCollectionIdentifier(projectName, collName), _ -> new GroupQueue());
    }

    private static void restoreInterrupt(boolean interrupted) {
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void passTurn(GroupQueue queue) {
        queue.monitor.lock();
        try {
            queue.leading = false;
            queue.turn.signalAll();
        } finally {
            queue.monitor.unlock();
        }
    }

    private static void publishRound(GroupQueue queue) {
        queue.monitor.lock();
        try {
            queue.turn.signalAll();
        } finally {
            queue.monitor.unlock();
        }
    }

    private static OperationResponse lead(String projectName, String collName, GroupQueue queue, GroupedWrite write,
            boolean interruptedBefore) {
        var interrupted = interruptedBefore;
        while (!write.served) {
            final var acquired = acquire(projectName, collName, queue, write);
            if (acquired == Acquired.WITHDRAWN) {
                return write.failure();
            }
            interrupted |= acquired == Acquired.HELD_AFTER_INTERRUPT;
            final var batch = takeBatch(queue);
            try {
                GroupedBatch.run(projectName, collName, batch);
            } catch (Throwable thrown) {
                failUnserved(projectName, collName, batch, thrown);
            } finally {
                locks.release(projectName, collName);
            }
            publishRound(queue);
        }
        restoreInterrupt(interrupted);
        return write.response;
    }

    private static void failUnserved(String projectName, String collName, List<GroupedWrite> batch, Throwable thrown) {
        GroupedBatch.closeAfterThrow(projectName, collName);
        for (final var taken : batch) {
            if (!taken.served) {
                taken.serve(taken.failure());
            }
        }
        logger.error("A write batch on " + Cache.getCollectionIdentifier(projectName, collName) + " threw; its "
                + batch.size() + " unanswered write(s) fail", thrown);
    }

    private static Acquired acquire(String projectName, String collName, GroupQueue queue, GroupedWrite write) {
        var interrupted = false;
        while (true) {
            try {
                locks.lock(projectName, collName);
                return interrupted ? Acquired.HELD_AFTER_INTERRUPT : Acquired.HELD;
            } catch (InterruptedException e) {
                if (queue.writes.remove(write)) {
                    Thread.currentThread().interrupt();
                    return Acquired.WITHDRAWN;
                }
                interrupted = true;
            }
        }
    }

    private static List<GroupedWrite> takeBatch(GroupQueue queue) {
        final var batch = new ArrayList<GroupedWrite>();
        final Map<String, GroupedWrite> byId = new HashMap<>();
        while (batch.size() < MAX_GROUP_OPS) {
            final var next = queue.writes.peek();
            if (next == null) {
                break;
            }
            final var earlier = next.id == null ? null : byId.get(next.id);
            if (earlier != null && (earlier.sequencedById() || next.sequencedById())) {
                break;
            }
            if (!queue.writes.remove(next)) {
                continue;
            }
            batch.add(next);
            if (next.id != null) {
                byId.put(next.id, next);
            }
        }
        return batch;
    }
}
