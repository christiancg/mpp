package org.ccg.mpp.ops.index;

import java.util.HashMap;
import java.util.Map;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.data.IdSet;
import org.ccg.mpp.ioc.IocContainer;

public final class CanonicalIds {
    @SuppressWarnings("FieldMayBeFinal")
    private static Cache cache = IocContainer.get(Cache.class);

    private final String projectName;
    private final String collName;
    private final Map<String, String> known = new HashMap<>();

    public CanonicalIds(String projectName, String collName) {
        this.projectName = projectName;
        this.collName = collName;
    }

    boolean heldIn(IdSet ids, String id) {
        final var canonical = of(id);
        return canonical == null || sameInstance(canonical, ids.stored(id));
    }

    private static boolean sameInstance(Object canonical, Object held) {
        return canonical == held;
    }

    String of(String id) {
        final var remembered = known.get(id);
        if (remembered != null || known.containsKey(id)) {
            return remembered;
        }
        final var canonical = cache.canonicalPkId(projectName, collName, id);
        known.put(id, canonical);
        return canonical;
    }
}
