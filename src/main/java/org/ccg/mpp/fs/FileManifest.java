package org.ccg.mpp.fs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.ccg.mpp.data.FileEntry;
import org.ccg.mpp.data.ProjectFilePath;

final class FileManifest {
    record PendingReplace(String tmpName, long version) {
    }

    record PendingMove(String from, String to) {
    }

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final TreeMap<String, FileEntry> entries = new TreeMap<>();
    private final Map<String, Long> tombstones = new HashMap<>();
    private final Map<String, Map<String, String>> childrenByFoldedName = new HashMap<>();
    private final Map<String, PendingReplace> pendingReplaces = new LinkedHashMap<>();
    private final List<PendingMove> pendingMoves = new ArrayList<>();
    private long incarnation;
    private long bytes;
    private long highestVersion;
    private int logLines;
    private boolean retired;

    ReentrantReadWriteLock lock() {
        return lock;
    }

    boolean isRetired() {
        return retired;
    }

    void retire() {
        retired = true;
    }

    long incarnation() {
        return incarnation;
    }

    void setIncarnation(long incarnation) {
        this.incarnation = incarnation;
    }

    long bytes() {
        return bytes;
    }

    int entryCount() {
        return entries.size();
    }

    long highestVersion() {
        return highestVersion;
    }

    int logLines() {
        return logLines;
    }

    void countLogLines(int added) {
        logLines += added;
    }

    void resetLogLines(int lines) {
        logLines = lines;
    }

    FileEntry get(ProjectFilePath path) {
        return entries.get(path.toString());
    }

    FileEntry get(String path) {
        return entries.get(path);
    }

    Long tombstone(String path) {
        return tombstones.get(path);
    }

    Map<String, Long> tombstones() {
        return tombstones;
    }

    NavigableMap<String, FileEntry> entries() {
        return entries;
    }

    NavigableMap<String, FileEntry> subtree(ProjectFilePath folder) {
        if (folder.isRoot()) {
            return entries;
        }
        final var lower = folder.subtreeLowerBound();
        return entries.subMap(lower, true, folder + "0", false);
    }

    boolean hasChildren(ProjectFilePath folder) {
        return !subtree(folder).isEmpty();
    }

    String siblingWithFoldedName(ProjectFilePath path) {
        final var children = childrenByFoldedName.get(path.parent().toString());
        return children == null ? null : children.get(path.foldedName());
    }

    Map<String, PendingReplace> pendingReplaces() {
        return pendingReplaces;
    }

    List<PendingMove> pendingMoves() {
        return pendingMoves;
    }

    void put(String path, FileEntry entry) {
        final var previous = entries.put(path, entry);
        if (previous != null) {
            bytes -= previous.size();
        } else {
            final var parsed = ProjectFilePath.ofStored(path);
            childrenByFoldedName.computeIfAbsent(parsed.parent().toString(), _ -> new HashMap<>())
                    .put(parsed.foldedName(), parsed.name());
        }
        bytes += entry.size();
        tombstones.remove(path);
        pendingReplaces.remove(path);
        observe(entry.version());
    }

    void putPending(String path, FileEntry entry, String tmpName) {
        put(path, entry);
        pendingReplaces.put(path, new PendingReplace(tmpName, entry.version()));
    }

    void bury(String path, long version) {
        final var previous = entries.remove(path);
        if (previous != null) {
            bytes -= previous.size();
            final var parsed = ProjectFilePath.ofStored(path);
            final var children = childrenByFoldedName.get(parsed.parent().toString());
            if (children != null) {
                children.remove(parsed.foldedName());
                if (children.isEmpty()) {
                    childrenByFoldedName.remove(parsed.parent().toString());
                }
            }
        }
        pendingReplaces.remove(path);
        final var existing = tombstones.get(path);
        if (existing == null || existing < version) {
            tombstones.put(path, version);
        }
        observe(version);
    }

    void forget(String path) {
        final var previous = entries.remove(path);
        if (previous == null) {
            return;
        }
        bytes -= previous.size();
        final var parsed = ProjectFilePath.ofStored(path);
        final var children = childrenByFoldedName.get(parsed.parent().toString());
        if (children != null) {
            children.remove(parsed.foldedName());
        }
    }

    void forgetTombstone(String path) {
        tombstones.remove(path);
    }

    void noteMove(String from, String to) {
        pendingMoves.add(new PendingMove(from, to));
    }

    void clearPendingWork() {
        pendingReplaces.clear();
        pendingMoves.clear();
    }

    private void observe(long version) {
        if (version > highestVersion) {
            highestVersion = version;
        }
    }
}
