package org.ccg.mpp.cache;

import java.util.function.Supplier;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.EntrypointDefinition;
import org.ccg.mpp.ejson.EJson;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ex.MetadataReadException;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.log.Logger;

public final class EntrypointDefinitions {
    private static final Logger logger = Logger.logFor(EntrypointDefinitions.class);
    private static final String MISS_PREFIX = "e" + Globals.COLL_IDENTIFIER_SEPARATOR;
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final EJson eJson = IocContainer.get(EJson.class);
    private final BoundedLruCache<EntrypointDefinition> values;
    private final DefinitionCache<EntrypointDefinition> cache;

    EntrypointDefinitions(long maxBytes, Supplier<PartitionedMissCache> misses) {
        values = new BoundedLruCache<>(Integer.MAX_VALUE, maxBytes,
                definition -> (long) eJson.toJson(definition.toJsonObject()).length() * 2L);
        cache = new DefinitionCache<>(() -> values, misses, MISS_PREFIX, this::loadUncached);
    }

    public EntrypointDefinition get(String projectName, String name) {
        return cache.get(projectName, name);
    }

    private EntrypointDefinition loadUncached(String projectName, String name) {
        try {
            final var raw = fs.metadataFiles().readEntrypoint(projectName, name);
            if (raw == null || raw.isBlank()) {
                return null;
            }
            return EntrypointDefinition.fromJsonObject(eJson.fromJson(raw, JsonObject.class));
        } catch (Exception e) {
            logger.warning("Failed to load entrypoint " + Cache.getCollectionIdentifier(projectName, name) + ": "
                    + e.getMessage());
            throw new MetadataReadException(
                    "Failed to read the entrypoint " + Cache.getCollectionIdentifier(projectName, name), e);
        }
    }

    public void put(String projectName, EntrypointDefinition definition) {
        cache.put(Cache.getCollectionIdentifier(projectName, definition.getName()), definition);
    }

    public void remove(String projectName, String name) {
        cache.remove(Cache.getCollectionIdentifier(projectName, name));
    }

    public void removeForProject(String projectName) {
        cache.removeForProject(projectName);
    }

    long bytes() {
        return values.bytes();
    }

    int size() {
        return values.size();
    }
}
