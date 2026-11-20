package org.ccg.mpp.ops;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.data.DbEntry;
import org.ccg.mpp.data.PkIndexEntry;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.index.IndexedValue;
import org.ccg.mpp.utils.JsonUtils;

final class PriorIndexedValues {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);

    private PriorIndexedValues() {
    }

    static Set<String> fieldsOf(String projectName, String collName) {
        return cache.fieldsKeepingPriorValues(projectName, collName);
    }

    static Map<String, IndexedValue> of(Set<String> fields, DbEntry previous) {
        if (fields.isEmpty()) {
            return null;
        }
        final var priors = new HashMap<String, IndexedValue>(fields.size() * 2);
        try {
            for (final var field : fields) {
                priors.put(field,
                        previous == null ? null : IndexedValue.of(JsonUtils.getFromPath(previous.getData(), field)));
            }
        } catch (RuntimeException e) {
            return null;
        }
        return priors;
    }

    static Map<String, IndexedValue> ofStored(Set<String> fields, String projectName, String collName,
            PkIndexEntry previousRow) {
        if (fields.isEmpty()) {
            return null;
        }
        try {
            return of(fields, cache.getById(projectName, collName, previousRow));
        } catch (Exception e) {
            return null;
        }
    }

    static Map<String, Map<String, IndexedValue>> ofRows(Set<String> fields, List<PkIndexEntry> previousRows,
            Collection<String> insertedIds) {
        if (fields.isEmpty()) {
            return null;
        }
        final var byId = new HashMap<String, Map<String, IndexedValue>>();
        if (!previousRows.isEmpty()) {
            try {
                for (final var previous : fs.getByIndexEntries(previousRows)) {
                    byId.put(previous.get_id(), of(fields, previous));
                }
            } catch (IOException e) {
                return null;
            }
        }
        final var none = of(fields, null);
        for (final var id : insertedIds) {
            byId.put(id, none);
        }
        return byId;
    }
}
