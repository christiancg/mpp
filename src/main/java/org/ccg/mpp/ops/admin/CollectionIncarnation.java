package org.ccg.mpp.ops.admin;

import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.ioc.IocContainer;

public final class CollectionIncarnation {
    private static final Cache cache = IocContainer.get(Cache.class);

    private CollectionIncarnation() {
    }

    public static long current(String projectName, String collName) {
        final var entry = cache.getAdminCollectionEntry(projectName, collName);
        return entry == null ? 0L : entry.getIncarnation();
    }

    public static boolean isCurrent(String projectName, String collName, long incarnation) {
        final var entry = cache.getAdminCollectionEntry(projectName, collName);
        return entry != null && sameLife(incarnation, entry.getIncarnation());
    }

    public static boolean sameLife(long recorded, long registered) {
        return recorded == 0L || registered == 0L || recorded == registered;
    }
}
