package org.ccg.mpp.cache;

import org.ccg.mpp.config.Globals;

public record CacheableResource(AccessKind kind, String dbName, String collName, String indexKey,
        long estimatedSizeBytes) {

    public static String indexedFieldOf(String indexKey) {
        final var separator = indexKey.lastIndexOf(Globals.COLL_IDENTIFIER_SEPARATOR);
        return separator < 0 ? indexKey : indexKey.substring(0, separator);
    }
}
