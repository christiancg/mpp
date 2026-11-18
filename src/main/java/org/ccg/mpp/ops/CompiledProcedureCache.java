package org.ccg.mpp.ops;

import java.util.function.Predicate;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.simplejs.CompiledScript;
import org.ccg.mpp.simplejs.SimpleJs;

public class CompiledProcedureCache {
    private final SimpleJs simpleJs = IocContainer.get(SimpleJs.class);
    private final CompiledProgramLru<CompiledScript> cache = new CompiledProgramLru<>();

    public CompiledScript get(String dbName, String name, long version, String source) {
        if (cache.isDisabled()) {
            return simpleJs.compile(source, false);
        }
        final var key = keyOf(dbName, name, version);
        final Predicate<CompiledScript> sameSource = compiled -> source.equals(compiled.source());
        final var cached = cache.get(key, sameSource);
        if (cached != null) {
            return cached;
        }
        return cache.admit(key, simpleJs.compile(source, false), source, sameSource);
    }

    public void invalidateDatabase(String dbName) {
        final var prefix = dbName + Globals.COLL_IDENTIFIER_SEPARATOR;
        cache.removeIf(key -> key.startsWith(prefix));
    }

    public void invalidateProcedure(String dbName, String name) {
        final var prefix = Cache.getCollectionIdentifier(dbName, name) + Globals.COLL_IDENTIFIER_SEPARATOR;
        cache.removeIf(key -> key.startsWith(prefix));
    }

    public int size() {
        return cache.size();
    }

    public long weightedBytes() {
        return cache.weightedBytes();
    }

    private static String keyOf(String dbName, String name, long version) {
        return Cache.getCollectionIdentifier(dbName, name) + Globals.COLL_IDENTIFIER_SEPARATOR + version;
    }
}
