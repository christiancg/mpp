package org.ccg.mpp.ops.entrypoint;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.data.EntrypointDefinition;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.log.Logger;

public class EntrypointRouter {
    private final Logger logger = Logger.logFor(EntrypointRouter.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final Map<String, RouteTable> tables = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public void loadAll() {
        for (final var projectName : new ArrayList<>(cache.getUserProjectNames())) {
            reload(projectName);
        }
    }

    public synchronized void reload(String projectName) {
        if (cache.getAdminProjectEntry(projectName) == null) {
            tables.remove(projectName);
            return;
        }
        final List<String> names;
        try {
            names = fs.metadataFiles().listEntrypointNames(projectName);
        } catch (IOException | RuntimeException e) {
            logger.warning("Kept the previous HTTP routes of project '" + projectName
                    + "': its entrypoints could not be listed: " + e.getMessage());
            return;
        }
        final var previous = table(projectName);
        final var definitions = new ArrayList<EntrypointDefinition>(names.size());
        for (final var name : names) {
            final var definition = readOrPrevious(projectName, name, previous);
            if (definition != null) {
                definitions.add(definition);
            }
        }
        tables.put(projectName,
                RouteTable.of(definitions, (definition, reason) -> warnOnce(projectName, definition, reason)));
    }

    private EntrypointDefinition readOrPrevious(String projectName, String name, RouteTable previous) {
        try {
            return cache.getEntrypoint(projectName, name);
        } catch (RuntimeException unreadable) {
            logger.warning("Kept the previous route of entrypoint '" + name + "' in project '" + projectName
                    + "': its definition could not be read: " + unreadable.getMessage());
            return previous.definition(name);
        }
    }

    private void warnOnce(String projectName, EntrypointDefinition definition, String reason) {
        final var key = warningPrefix(projectName) + definition.getName() + "|" + definition.getVersion();
        if (warned.add(key)) {
            logger.warning("Entrypoint '" + definition.getName() + "' in project '" + projectName + "' is not routed: "
                    + reason);
        }
    }

    public void forgetProject(String projectName) {
        tables.remove(projectName);
        final var prefix = warningPrefix(projectName);
        warned.removeIf(key -> key.startsWith(prefix));
    }

    private static String warningPrefix(String projectName) {
        return projectName + "|";
    }

    public RouteTable table(String projectName) {
        return tables.getOrDefault(projectName, RouteTable.EMPTY);
    }
}
