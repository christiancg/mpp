package org.ccg.mpp.cache;

import java.io.IOException;
import java.util.function.Function;
import java.util.function.Supplier;
import org.ccg.mpp.data.StoredDefinition;
import org.ccg.mpp.ejson.EJson;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ex.MetadataReadException;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.log.Logger;

public final class NamedDefinitions<T extends StoredDefinition> {
    private static final Logger logger = Logger.logFor(NamedDefinitions.class);
    private final EJson eJson = IocContainer.get(EJson.class);
    private final String kind;
    private final DefinitionReader reader;
    private final Function<JsonObject, T> parser;
    private final BoundedLruCache<T> values;
    private final DefinitionCache<T> cache;

    @FunctionalInterface
    interface DefinitionReader {
        String read(String projectName, String name) throws IOException;
    }

    record Shape<T>(String kind, String missPrefix, DefinitionReader reader, Function<JsonObject, T> parser,
            Function<T, JsonObject> writer) {
    }

    NamedDefinitions(long maxBytes, Supplier<PartitionedMissCache> misses, Shape<T> shape) {
        this.kind = shape.kind();
        this.reader = shape.reader();
        this.parser = shape.parser();
        values = new BoundedLruCache<>(Integer.MAX_VALUE, maxBytes,
                definition -> (long) eJson.toJson(shape.writer().apply(definition)).length() * 2L);
        cache = new DefinitionCache<>(() -> values, misses, shape.missPrefix(), this::loadUncached);
    }

    public T get(String projectName, String name) {
        return cache.get(projectName, name);
    }

    private T loadUncached(String projectName, String name) {
        try {
            final var raw = reader.read(projectName, name);
            if (raw == null || raw.isBlank()) {
                return null;
            }
            return parser.apply(eJson.fromJson(raw, JsonObject.class));
        } catch (Exception e) {
            logger.warning("Failed to load " + kind + " " + Cache.getCollectionIdentifier(projectName, name) + ": "
                    + e.getMessage());
            throw new MetadataReadException(
                    "Failed to read the " + kind + " " + Cache.getCollectionIdentifier(projectName, name), e);
        }
    }

    public void put(String projectName, T definition) {
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
