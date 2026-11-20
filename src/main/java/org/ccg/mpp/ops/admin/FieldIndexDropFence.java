package org.ccg.mpp.ops.admin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.ex.CollectionBusyException;
import org.ccg.mpp.ioc.IocContainer;

public final class FieldIndexDropFence {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);

    @FunctionalInterface
    public interface FencedAction<T> {
        T run() throws Exception;
    }

    private record HeldField(String collName, String fieldName) {
    }

    private FieldIndexDropFence() {
    }

    public static <T> T holdingFieldIndexLocks(String projectName, Collection<String> collNames, long timeoutMillis,
            FencedAction<T> action) throws Exception {
        final var held = new ArrayList<HeldField>();
        try {
            lockEveryField(projectName, collNames, timeoutMillis, held);
            return action.run();
        } finally {
            release(projectName, held);
        }
    }

    private static void lockEveryField(String projectName, Collection<String> collNames, long timeoutMillis,
            List<HeldField> held) throws InterruptedException {
        final var deadline = System.currentTimeMillis() + Math.max(0L, timeoutMillis);
        for (final var collName : new TreeSet<>(collNames)) {
            for (final var fieldName : new TreeSet<>(cache.getIndexesForCollection(projectName, collName))) {
                final var remaining = Math.max(0L, deadline - System.currentTimeMillis());
                if (!locks.tryLockIndex(projectName, collName, fieldName, remaining)) {
                    throw new CollectionBusyException(Cache.getCollectionIdentifier(projectName, collName)
                            + Globals.COLL_IDENTIFIER_SEPARATOR + fieldName, timeoutMillis);
                }
                held.add(new HeldField(collName, fieldName));
            }
        }
    }

    private static void release(String projectName, List<HeldField> held) {
        for (final var field : held.reversed()) {
            locks.releaseIndex(projectName, field.collName(), field.fieldName());
        }
    }
}
