package org.ccg.mpp.ops.index;

import java.util.ArrayList;
import java.util.List;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;

public final class MaintainedIndexes {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);

    private MaintainedIndexes() {
    }

    public static List<String> of(String projectName, String collName) {
        final var registered = cache.getIndexesForCollection(projectName, collName);
        final var maintained = new ArrayList<String>(registered.size());
        for (final var fieldName : registered) {
            if (!fs.indexMarkers().isMarked(projectName, collName, fieldName)) {
                maintained.add(fieldName);
            }
        }
        return maintained;
    }

    public static boolean none(String projectName, String collName) {
        for (final var fieldName : cache.getIndexesForCollection(projectName, collName)) {
            if (!fs.indexMarkers().isMarked(projectName, collName, fieldName)) {
                return false;
            }
        }
        return true;
    }
}
