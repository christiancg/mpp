package org.ccg.mpp.ops;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.admin.AdminProjectEntry;
import org.ccg.mpp.ioc.IocContainer;

public final class OnDiskNameRegistry {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final List<String> RESERVED_PROJECT_NAMES = List.of(Globals.ADMIN_PROJECT_NAME,
            Globals.ADMIN_PAGES_PROJECT_NAME, Globals.CLUSTER_FOLDER);

    private OnDiskNameRegistry() {
    }

    public static String onDiskKey(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public static String collidingProject(String projectName) {
        return firstColliding(allProjectNames(), projectName);
    }

    public static String collidingCollection(String projectName, String collName) {
        return firstColliding(collectionNamesOf(projectName), collName);
    }

    public static String collidingIndexField(String projectName, String collName, String fieldName) {
        return firstColliding(cache.getIndexesForCollection(projectName, collName), fieldName);
    }

    public static String collidingDefinition(Collection<String> existing, String name) {
        return firstColliding(existing, name);
    }

    public static List<List<String>> groupedByOnDiskKey() {
        final var groups = new ArrayList<List<String>>();
        addGroupsOf(groups, allProjectNames());
        for (final var projectName : allProjectNames()) {
            addGroupsOf(groups, collectionNamesOf(projectName));
        }
        return groups;
    }

    public static List<String> registeredProjectsInTheClusterFolder() {
        final var names = new ArrayList<String>();
        for (final var entry : cache.getAllAdminProjectEntries()) {
            final var name = entry.get_id();
            if (name != null && onDiskKey(name).equals(Globals.CLUSTER_FOLDER)) {
                names.add(name);
            }
        }
        return names;
    }

    private static void addGroupsOf(List<List<String>> groups, Collection<String> names) {
        final var byKey = new LinkedHashMap<String, List<String>>();
        for (final var name : names) {
            byKey.computeIfAbsent(onDiskKey(name), _ -> new ArrayList<>()).add(name);
        }
        for (final var group : byKey.values()) {
            if (group.size() > 1) {
                groups.add(group);
            }
        }
    }

    private static String firstColliding(Collection<String> existingNames, String candidate) {
        if (candidate == null) {
            return null;
        }
        final var candidateKey = onDiskKey(candidate);
        for (final var existing : existingNames) {
            if (!existing.equals(candidate) && onDiskKey(existing).equals(candidateKey)) {
                return existing;
            }
        }
        return null;
    }

    private static List<String> allProjectNames() {
        final var names = new ArrayList<>(RESERVED_PROJECT_NAMES);
        for (final var entry : cache.getAllAdminProjectEntries()) {
            final var name = entry.get_id();
            if (name != null && !names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    private static List<String> collectionNamesOf(String projectName) {
        final var entry = cache.getAdminProjectEntry(projectName);
        return entry == null ? List.of() : collectionsOf(entry);
    }

    private static List<String> collectionsOf(AdminProjectEntry entry) {
        final var collections = entry.getCollections();
        return collections == null ? List.of() : collections;
    }
}
