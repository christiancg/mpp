package org.ccg.mpp.ops;

import java.util.List;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.cluster.ClusterConfig;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.resp.OperationResponse;

public final class CollectionReadinessGuard {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);

    private CollectionReadinessGuard() {
    }

    public static OperationResponse check(OperationType type, String projectName, String collName) {
        if (cache.getAdminCollectionEntry(projectName, collName) != null) {
            return null;
        }
        return clusterConfig.isEnabled()
                ? new OperationResponse(type, ErrorCode.COLLECTION_NOT_READY)
                : new OperationResponse(type, ErrorCode.COLLECTION_NOT_FOUND);
    }

    public static OperationResponse precheck(OperationType type, String projectName, String collName) {
        try {
            return check(type, projectName, collName);
        } catch (RuntimeException deferredToTheLockedCheck) {
            return null;
        }
    }

    public static OperationResponse precheckRead(OperationType type, String projectName, List<String> collNames) {
        try {
            return checkRead(type, projectName, collNames);
        } catch (RuntimeException deferredToTheLockedCheck) {
            return null;
        }
    }

    public static OperationResponse checkRead(OperationType type, String projectName, List<String> collNames) {
        if (isAdminProject(projectName)) {
            return null;
        }
        for (final var collName : collNames) {
            if (!Globals.SCRIPT_RUNS_COLLECTION_NAME.equals(collName)) {
                final var refusal = check(type, projectName, collName);
                if (refusal != null) {
                    return refusal;
                }
            }
        }
        return null;
    }

    private static boolean isAdminProject(String projectName) {
        return Globals.ADMIN_PROJECT_NAME.equals(projectName) || Globals.ADMIN_PAGES_PROJECT_NAME.equals(projectName);
    }
}
