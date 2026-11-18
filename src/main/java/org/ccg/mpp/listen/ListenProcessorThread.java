package org.ccg.mpp.listen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Predicate;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.ConfigKey;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.conn.ClientTracker;
import org.ccg.mpp.conn.OutboundQueue;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.log.Logger;
import org.ccg.mpp.ops.AggregateLimitException;
import org.ccg.mpp.ops.AggregationOperationHelper;
import org.ccg.mpp.ops.auth.AuthorizationChecker;
import org.ccg.mpp.ops.resp.ListenEndedResponse;

public class ListenProcessorThread implements Runnable {
    private final Logger logger = Logger.logFor(ListenProcessorThread.class);
    private final LinkedBlockingQueue<UUID> dirtyQueue;
    private final ListenManager manager;
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final Configuration configuration = Configuration.getInstance();

    public ListenProcessorThread(LinkedBlockingQueue<UUID> dirtyQueue, ListenManager manager) {
        this.dirtyQueue = dirtyQueue;
        this.manager = manager;
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                final var workId = dirtyQueue.take();
                manager.dequeued(workId);
                process(workId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception | Error e) {
                logger.error("Error in listen processor thread: ", e);
            }
        }
    }

    private void process(UUID workId) {
        final var endedListen = manager.endedListen(workId);
        if (endedListen != null) {
            pushEndedOnceDelivered(workId, endedListen);
            return;
        }
        final var group = manager.group(workId);
        if (group == null || !group.tryStartRun()) {
            return;
        }
        try {
            runGroup(group);
        } finally {
            if (group.finishRun()) {
                manager.enqueueWork(group.id());
            }
        }
    }

    private void runGroup(ListenGroup group) {
        final var members = deliveredAuthorizedMembers(group);
        if (members.isEmpty()) {
            return;
        }
        final var results = rerunOutsideAnyApply(group);
        if (results == null) {
            return;
        }
        try (var encoded = ResultHasher.encode(results, group.ordered())) {
            final var newHash = encoded.hash();
            for (final var registration : members) {
                final var oldHash = registration.lastHash().get();
                if (newHash.equals(oldHash) || !registration.lastHash().compareAndSet(oldHash, newHash)) {
                    continue;
                }
                final var split = ListenPushFrames.split(registration.listenId(), newHash);
                push(registration.listenId(), registration.clientId(),
                        outbound -> outbound.offer(split.head(), encoded.body(), split.tail()), registration);
            }
        }
    }

    private List<ListenRegistration> deliveredAuthorizedMembers(ListenGroup group) {
        final var members = new ArrayList<ListenRegistration>();
        final var allowedByUser = new HashMap<String, Boolean>();
        for (final var listenId : group.members()) {
            final var registration = manager.getRegistration(listenId);
            if (registration == null) {
                continue;
            }
            if (!stillAuthorized(registration, group, allowedByUser)) {
                manager.end(listenId, ListenManager.ACCESS_REVOKED);
                continue;
            }
            if (registration.delivered().get()) {
                members.add(registration);
            }
        }
        return members;
    }

    private boolean stillAuthorized(ListenRegistration registration, ListenGroup group,
            HashMap<String, Boolean> allowedByUser) {
        final var username = clientTracker.getAuthenticatedUsername(registration.clientId());
        if (username == null) {
            return false;
        }
        return allowedByUser.computeIfAbsent(username, _ -> {
            final var user = cache.getAdminUserEntry(username);
            return user != null && AuthorizationChecker.check(group.request(), user).isAllowed();
        });
    }

    private List<JsonObject> rerunOutsideAnyApply(ListenGroup group) {
        final var before = manager.applySnapshot(group.collectionKeys());
        if (before == null) {
            manager.holdBack(group.id(), group.collectionKeys());
            return null;
        }
        final List<JsonObject> results;
        try {
            results = AggregationOperationHelper.processAggregation(group.request(),
                    configuration.longValue(ConfigKey.LISTEN_RERUN_TIMEOUT_MS));
        } catch (AggregateLimitException refused) {
            logger.warning("Listen query " + group.id() + " was not re-run: " + refused.getMessage());
            return null;
        } catch (Exception e) {
            logger.error("Error re-running listen query " + group.id(), e);
            return null;
        }
        if (!manager.unchangedSince(before)) {
            manager.holdBack(group.id(), group.collectionKeys());
            return null;
        }
        return results;
    }

    private void pushEndedOnceDelivered(UUID listenId, ListenManager.EndedListen endedListen) {
        final var registration = endedListen.registration();
        if (registration.delivered().get() && manager.consumeEnded(listenId, endedListen)) {
            final var frame = OutboundQueue.frameOf(new ListenEndedResponse(listenId.toString(), endedListen.reason()));
            push(listenId, registration.clientId(), outbound -> outbound.offer(frame), null);
        }
    }

    private void push(UUID listenId, UUID clientId, Predicate<OutboundQueue> offer, ListenRegistration expected) {
        final var outbound = clientTracker.getOutbound(clientId);
        final var writerLock = clientTracker.getWriterLock(clientId);
        if (outbound == null || writerLock == null) {
            manager.unregister(listenId);
            return;
        }
        writerLock.lock();
        try {
            if (expected != null && manager.getRegistration(listenId) != expected) {
                return;
            }
            if (!offer.test(outbound)) {
                manager.unregister(listenId);
            }
        } finally {
            writerLock.unlock();
        }
    }
}
