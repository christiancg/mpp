package org.ccg.mpp.ops.index;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.DbEntry;
import org.ccg.mpp.ex.IndexLockBusyException;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.utils.FieldPath;

public final class FieldMaintenance {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ResourceLocking rl = IocContainer.get(ResourceLocking.class);
    private static final Configuration configuration = Configuration.getInstance();

    private FieldMaintenance() {
    }

    public static List<DeferredFold> maintain(String dbName, String collName, String fieldName, Collection<String> ids,
            Map<String, DbEntry> byId, Map<String, List<IndexedValue>> priors, CanonicalIds canonicalIds)
            throws IOException, InterruptedException {
        return maintain(dbName, collName, fieldName, ids, byId, priors, null, canonicalIds);
    }

    static List<DeferredFold> maintain(String dbName, String collName, String fieldName, Collection<String> ids,
            Map<String, DbEntry> byId, Map<String, List<IndexedValue>> priors, HeldTypeLists held,
            CanonicalIds canonicalIds) throws IOException, InterruptedException {
        final var budget = configuration.getTransactionLockTimeoutMs();
        if (!rl.tryLockIndex(dbName, collName, fieldName, budget)) {
            throw new IndexLockBusyException(
                    Cache.getCollectionIdentifier(dbName, collName) + Globals.COLL_IDENTIFIER_SEPARATOR + fieldName,
                    budget);
        }
        var flushed = false;
        try {
            final var batch = FieldIndexBatch.open(dbName, collName, fieldName, ids, priors, held, canonicalIds);
            final var path = FieldPath.of(fieldName);
            for (var id : ids) {
                final var doc = byId.get(id);
                batch.apply(id, doc == null ? null : path.valueIn(doc.getData()));
            }
            final var folds = batch.flushLeavingFolds();
            if (held != null) {
                held.chunkFlushed();
            }
            flushed = true;
            return folds;
        } finally {
            if (!flushed) {
                cache.evictFieldIndexAllTypes(dbName, collName, fieldName);
                if (held != null) {
                    held.clear();
                }
            }
            rl.releaseIndex(dbName, collName, fieldName);
        }
    }
}
