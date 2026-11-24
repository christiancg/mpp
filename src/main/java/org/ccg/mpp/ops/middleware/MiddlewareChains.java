package org.ccg.mpp.ops.middleware;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.data.EntrypointDefinition;
import org.ccg.mpp.data.MiddlewareDefinition;
import org.ccg.mpp.data.MiddlewarePhase;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.log.Logger;

public class MiddlewareChains {
    public static final Comparator<MiddlewareDefinition> EXECUTION_ORDER = Comparator
            .comparing(MiddlewareDefinition::getPhase).thenComparingInt(MiddlewareDefinition::getOrder)
            .thenComparing(MiddlewareDefinition::getName);
    private final Logger logger = Logger.logFor(MiddlewareChains.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final Map<String, ProjectMiddlewares> projects = new ConcurrentHashMap<>();

    public sealed interface ProjectMiddlewares {
        record Loaded(List<MiddlewareDefinition> sorted) implements ProjectMiddlewares {
            public Loaded {
                sorted = List.copyOf(sorted);
            }
        }

        record Unreadable(String reason) implements ProjectMiddlewares {
        }
    }

    private static final ProjectMiddlewares NONE = new ProjectMiddlewares.Loaded(List.of());

    public void loadAll() {
        for (final var projectName : new ArrayList<>(cache.getUserProjectNames())) {
            reload(projectName);
        }
    }

    public synchronized void reload(String projectName) {
        if (cache.getAdminProjectEntry(projectName) == null) {
            projects.remove(projectName);
            return;
        }
        final List<String> names;
        try {
            names = fs.metadataFiles().listMiddlewareNames(projectName);
        } catch (IOException | RuntimeException e) {
            if (projects.containsKey(projectName)) {
                logger.warning("Kept the previous middlewares of project '" + projectName
                        + "': they could not be listed: " + e.getMessage());
            } else {
                markUnreadable(projectName, "the middlewares could not be listed: " + e.getMessage());
            }
            return;
        }
        final var previous = projects.get(projectName);
        final var definitions = new ArrayList<MiddlewareDefinition>(names.size());
        for (final var name : names) {
            final MiddlewareDefinition definition;
            try {
                definition = readable(projectName, name);
            } catch (MiddlewareUnreadableException unreadable) {
                final var kept = previousOf(previous, name);
                if (kept == null) {
                    markUnreadable(projectName, unreadable.getMessage());
                    return;
                }
                definitions.add(kept);
                continue;
            }
            if (definition != null) {
                definitions.add(definition);
            }
        }
        definitions.sort(EXECUTION_ORDER);
        projects.put(projectName, new ProjectMiddlewares.Loaded(definitions));
    }

    private MiddlewareDefinition readable(String projectName, String name) throws MiddlewareUnreadableException {
        final MiddlewareDefinition definition;
        try {
            definition = cache.getMiddleware(projectName, name);
        } catch (RuntimeException e) {
            throw new MiddlewareUnreadableException(
                    "the middleware '" + name + "' could not be read: " + e.getMessage());
        }
        if (definition != null && (definition.getPhase() == null || definition.getProcedureName() == null)) {
            throw new MiddlewareUnreadableException("the middleware '" + name + "' has no phase or procedure");
        }
        return definition;
    }

    private static MiddlewareDefinition previousOf(ProjectMiddlewares previous, String name) {
        if (previous instanceof ProjectMiddlewares.Loaded(var sorted)) {
            return sorted.stream().filter(definition -> definition.getName().equals(name)).findFirst().orElse(null);
        }
        return null;
    }

    private void markUnreadable(String projectName, String reason) {
        logger.error("Every entrypoint of project '" + projectName + "' answers 500 until its middlewares can be read: "
                + reason);
        projects.put(projectName, new ProjectMiddlewares.Unreadable(reason));
    }

    private static final class MiddlewareUnreadableException extends Exception {
        MiddlewareUnreadableException(String reason) {
            super(reason);
        }
    }

    public void forgetProject(String projectName) {
        projects.remove(projectName);
    }

    public List<MiddlewareDefinition> sorted(String projectName) {
        return projects.getOrDefault(projectName, NONE) instanceof ProjectMiddlewares.Loaded(var sorted)
                ? sorted
                : List.of();
    }

    public MiddlewareChain chainFor(String projectName, EntrypointDefinition entrypoint) {
        return switch (projects.getOrDefault(projectName, NONE)) {
            case ProjectMiddlewares.Unreadable(var reason) -> MiddlewareChain.unreadable(reason);
            case ProjectMiddlewares.Loaded(var sorted) -> chainOf(sorted, entrypoint);
        };
    }

    private static MiddlewareChain chainOf(List<MiddlewareDefinition> sorted, EntrypointDefinition entrypoint) {
        if (sorted.isEmpty()) {
            return MiddlewareChain.EMPTY;
        }
        final var ingress = new ArrayList<MiddlewareDefinition>();
        final var egress = new ArrayList<MiddlewareDefinition>();
        for (final var definition : sorted) {
            if (definition.isSkipped() || !definition.getMatch().wraps(entrypoint)) {
                continue;
            }
            (definition.getPhase() == MiddlewarePhase.INGRESS ? ingress : egress).add(definition);
        }
        return new MiddlewareChain(ingress, egress, null);
    }
}
