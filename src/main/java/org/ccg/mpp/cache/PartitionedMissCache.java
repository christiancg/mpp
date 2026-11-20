package org.ccg.mpp.cache;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

public class PartitionedMissCache {
    public static final int MAX_ENTRIES_PER_PROJECT = 256;

    private final Map<String, BoundedLruCache<Boolean>> partitions = new LinkedHashMap<>(16, 0.75f, true);
    private final int maxEntries;
    private final int maxEntriesPerProject;

    public PartitionedMissCache(int maxEntries) {
        this.maxEntries = maxEntries;
        this.maxEntriesPerProject = Math.min(MAX_ENTRIES_PER_PROJECT, maxEntries);
    }

    public synchronized boolean contains(String projectName, String key) {
        final var partition = partitions.get(projectName);
        return partition != null && partition.get(key) != null;
    }

    public synchronized void put(String projectName, String key) {
        if (maxEntries <= 0) {
            return;
        }
        partitions.computeIfAbsent(projectName, _ -> new BoundedLruCache<>(maxEntriesPerProject, 0L, _ -> 1L)).put(key,
                Boolean.TRUE);
        evictPartitionsDownToBound();
    }

    public synchronized void remove(String projectName, String key) {
        final var partition = partitions.get(projectName);
        if (partition == null) {
            return;
        }
        partition.remove(key);
        dropIfEmpty(projectName, partition);
    }

    public synchronized void removeIf(String projectName, Predicate<String> keyMatches) {
        final var partition = partitions.get(projectName);
        if (partition == null) {
            return;
        }
        partition.removeIf(keyMatches);
        dropIfEmpty(projectName, partition);
    }

    public synchronized void clear() {
        partitions.clear();
    }

    public synchronized int size() {
        var total = 0;
        for (var partition : partitions.values()) {
            total += partition.size();
        }
        return total;
    }

    private void dropIfEmpty(String projectName, BoundedLruCache<Boolean> partition) {
        if (partition.size() == 0) {
            partitions.remove(projectName);
        }
    }

    private void evictPartitionsDownToBound() {
        final var iterator = partitions.values().iterator();
        while (partitions.size() > 1 && size() > maxEntries && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }
}
