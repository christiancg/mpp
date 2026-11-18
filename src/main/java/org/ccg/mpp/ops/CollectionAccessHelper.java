package org.ccg.mpp.ops;

import org.ccg.mpp.cache.AccessKind;
import org.ccg.mpp.cache.MemoryManagement;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.ioc.IocContainer;

public final class CollectionAccessHelper {
    private static final MemoryManagement memoryManagement = IocContainer.get(MemoryManagement.class);

    private CollectionAccessHelper() {
    }

    public static void recordCollectionAccess(String dbName, String collName) {
        recordAccess(AccessKind.COLLECTION, dbName, collName);
    }

    public static void recordPkIndexAccess(String dbName, String collName) {
        recordAccess(AccessKind.PK_INDEX, dbName, collName);
    }

    private static void recordAccess(AccessKind kind, String dbName, String collName) {
        if (Globals.ADMIN_DB_NAME.equals(dbName)) {
            return;
        }
        memoryManagement.recordAccess(kind, dbName, collName, null);
    }
}
