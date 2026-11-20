package org.ccg.mpp.ops;

import org.ccg.mpp.cache.AccessKind;
import org.ccg.mpp.cache.MemoryManagement;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.ioc.IocContainer;

public final class CollectionAccessHelper {
    private static final MemoryManagement memoryManagement = IocContainer.get(MemoryManagement.class);

    private CollectionAccessHelper() {
    }

    public static void recordCollectionAccess(String projectName, String collName) {
        recordAccess(AccessKind.COLLECTION, projectName, collName);
    }

    public static void recordPkIndexAccess(String projectName, String collName) {
        recordAccess(AccessKind.PK_INDEX, projectName, collName);
    }

    private static void recordAccess(AccessKind kind, String projectName, String collName) {
        if (Globals.ADMIN_PROJECT_NAME.equals(projectName)) {
            return;
        }
        memoryManagement.recordAccess(kind, projectName, collName, null);
    }
}
