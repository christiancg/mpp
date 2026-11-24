package org.ccg.mpp.cache;

import java.util.function.Supplier;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.EntrypointDefinition;
import org.ccg.mpp.data.MiddlewareDefinition;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;

public final class HttpDefinitionCaches {
    private static final String ENTRYPOINT_MISS_PREFIX = "e" + Globals.COLL_IDENTIFIER_SEPARATOR;
    private static final String MIDDLEWARE_MISS_PREFIX = "m" + Globals.COLL_IDENTIFIER_SEPARATOR;
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final NamedDefinitions<EntrypointDefinition> entrypoints;
    private final NamedDefinitions<MiddlewareDefinition> middlewares;

    HttpDefinitionCaches(long maxBytesEach, Supplier<PartitionedMissCache> misses) {
        entrypoints = new NamedDefinitions<>(maxBytesEach, misses,
                new NamedDefinitions.Shape<>("entrypoint", ENTRYPOINT_MISS_PREFIX,
                        (projectName, name) -> fs.metadataFiles().readEntrypoint(projectName, name),
                        EntrypointDefinition::fromJsonObject, EntrypointDefinition::toJsonObject));
        middlewares = new NamedDefinitions<>(maxBytesEach, misses,
                new NamedDefinitions.Shape<>("middleware", MIDDLEWARE_MISS_PREFIX,
                        (projectName, name) -> fs.metadataFiles().readMiddleware(projectName, name),
                        MiddlewareDefinition::fromJsonObject, MiddlewareDefinition::toJsonObject));
    }

    public NamedDefinitions<EntrypointDefinition> entrypoints() {
        return entrypoints;
    }

    public NamedDefinitions<MiddlewareDefinition> middlewares() {
        return middlewares;
    }

    public void removeForProject(String projectName) {
        entrypoints.removeForProject(projectName);
        middlewares.removeForProject(projectName);
    }
}
