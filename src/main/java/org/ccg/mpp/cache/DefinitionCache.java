package org.ccg.mpp.cache;

import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.ccg.mpp.config.Globals;

// Misses live in a cache separate from the values, so a caller naming thousands of nonexistent
// definitions cannot evict the ones in use.
final class DefinitionCache<T> {
    private final Supplier<BoundedLruCache<T>> values;
    private final Supplier<PartitionedMissCache> misses;
    private final String missPrefix;
    private final BiFunction<String, String, T> loader;
    private final GenerationGuard generation = new GenerationGuard();

    // Suppliers, not direct references: AdminCache owns these caches and may replace an instance.
    DefinitionCache(Supplier<BoundedLruCache<T>> values, Supplier<PartitionedMissCache> misses, String missPrefix,
            BiFunction<String, String, T> loader) {
        this.values = values;
        this.misses = misses;
        this.missPrefix = missPrefix;
        this.loader = loader;
    }

    T get(String projectName, String name) {
        final var id = Cache.getCollectionIdentifier(projectName, name);
        final var cached = values.get().get(id);
        if (cached != null) {
            return cached;
        }
        if (misses.get().contains(projectName, missPrefix + id)) {
            return null;
        }
        final var generationAtLoad = generation.current();
        final var loaded = loader.apply(projectName, name);
        generation.publishIfCurrent(generationAtLoad, () -> publishLoad(projectName, id, loaded));
        return loaded;
    }

    private void publishLoad(String projectName, String id, T loaded) {
        if (loaded == null) {
            misses.get().put(projectName, missPrefix + id);
        } else {
            values.get().put(id, loaded);
        }
    }

    void put(String id, T value) {
        generation.invalidate(() -> {
            misses.get().remove(projectOf(id), missPrefix + id);
            values.get().put(id, value);
        });
    }

    void remove(String id) {
        generation.invalidate(() -> {
            values.get().remove(id);
            misses.get().remove(projectOf(id), missPrefix + id);
        });
    }

    void removeForProject(String projectName) {
        final var prefix = projectName + Globals.COLL_IDENTIFIER_SEPARATOR;
        generation.invalidate(() -> {
            values.get().removeIf(id -> id.startsWith(prefix));
            misses.get().removeIf(projectName, id -> id.startsWith(missPrefix + prefix));
        });
    }

    void removeIf(Predicate<String> keyMatches) {
        generation.invalidate(() -> values.get().removeIf(keyMatches));
    }

    private static String projectOf(String id) {
        final var separator = id.indexOf(Globals.COLL_IDENTIFIER_SEPARATOR);
        return separator < 0 ? id : id.substring(0, separator);
    }
}
