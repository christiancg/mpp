package org.ccg.mpp.fs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.log.Logger;

public final class IndexMarkers {
    private static final String BUILDING_SUFFIX = ".building";
    private static final String DIRTY_SUFFIX = "-indexes.dirty";
    private static final String UNCLEAN_STOP_SUFFIX = "-indexes.unclean";
    private static final Logger logger = Logger.logFor(IndexMarkers.class);

    private final FilePaths paths;
    private final Set<String> marked = ConcurrentHashMap.newKeySet();
    private final Set<String> dirtyWritten = ConcurrentHashMap.newKeySet();
    private final ReentrantLock loadLock = new ReentrantLock();
    private volatile String loadedFor;

    IndexMarkers(FilePaths paths) {
        this.paths = paths;
    }

    public void mark(String projectName, String collName, String fieldName) throws IOException {
        ensureLoaded();
        final var marker = buildingFile(projectName, collName, fieldName);
        if (!marker.getParentFile().exists()) {
            return;
        }
        touch(marker);
        marked.add(keyOf(projectName, collName, fieldName));
    }

    public void clear(String projectName, String collName, String fieldName) {
        ensureLoaded();
        if (delete(buildingFile(projectName, collName, fieldName), keyOf(projectName, collName, fieldName))) {
            marked.remove(keyOf(projectName, collName, fieldName));
        }
    }

    public boolean isMarked(String projectName, String collName, String fieldName) {
        ensureLoaded();
        if (marked.isEmpty()) {
            return false;
        }
        final var key = keyOf(projectName, collName, fieldName);
        if (!marked.contains(key)) {
            return false;
        }
        if (buildingFile(projectName, collName, fieldName).exists()) {
            return true;
        }
        marked.remove(key);
        return false;
    }

    public List<String> listMarked() {
        ensureLoaded();
        final var result = new ArrayList<String>();
        for (final var key : marked) {
            final var parts = key.split("\\" + Globals.COLL_IDENTIFIER_SEPARATOR, 3);
            if (isMarked(parts[0], parts[1], parts[2])) {
                result.add(key);
            }
        }
        result.sort(String::compareTo);
        return result;
    }

    public void markDirty(String projectName, String collName) throws IOException {
        final var marker = dirtyFile(projectName, collName);
        if (marker.getParentFile().exists()) {
            dirtyWritten.add(projectName + "|" + collName);
            touch(marker);
        }
    }

    public void clearDirty(String projectName, String collName) {
        if (dirtyWritten.contains(projectName + "|" + collName)) {
            retireDirty(projectName, collName);
        }
    }

    public void retireDirty(String projectName, String collName) {
        final var owner = projectName + "|" + collName;
        final var marker = dirtyFile(projectName, collName);
        if (marker.exists() && !indexLogsForced(projectName, collName)) {
            return;
        }
        if (delete(marker, owner)) {
            dirtyWritten.remove(owner);
        }
    }

    private boolean indexLogsForced(String projectName, String collName) {
        final var group = new ForceGroup();
        FieldIndexLog.forceUnforced(paths.collectionFolder(projectName, collName), group);
        try {
            group.force();
            return true;
        } catch (IOException e) {
            logger.warning(
                    "Kept the index-dirty marker of " + projectName + "|" + collName + ": its field-index logs could"
                            + " not be forced, so the next startup still repairs that work: " + e.getMessage());
            return false;
        }
    }

    public void clearUncleanStop(String projectName, String collName) {
        delete(uncleanStopFile(projectName, collName), projectName + "|" + collName);
    }

    public List<String> retainUncleanStops() {
        final var retained = new ArrayList<String>();
        for (final var collection : liveCollections()) {
            final var projectName = collection.getParentFile().getName();
            final var collName = collection.getName();
            final var marker = dirtyFile(projectName, collName);
            final var uncleanStop = uncleanStopFile(projectName, collName);
            if (marker.exists()) {
                if (uncleanStop.exists() || retain(marker, uncleanStop)) {
                    delete(marker, projectName + "|" + collName);
                } else {
                    logger.warning("Could not retain the unclean-stop marker for " + projectName + "|" + collName
                            + "; the next write to it may retire the warning before REINDEX runs");
                }
            }
            if (marker.exists() || uncleanStop.exists()) {
                retained.add(projectName + Globals.COLL_IDENTIFIER_SEPARATOR + collName);
            }
        }
        return retained;
    }

    private void ensureLoaded() {
        final var dbPath = paths.dbPath();
        if (dbPath == null || dbPath.equals(loadedFor)) {
            return;
        }
        loadLock.lock();
        try {
            if (dbPath.equals(loadedFor)) {
                return;
            }
            marked.clear();
            for (final var collection : liveCollections()) {
                loadCollection(collection.getParentFile().getName(), collection);
            }
            loadedFor = dbPath;
        } finally {
            loadLock.unlock();
        }
    }

    private void loadCollection(String projectName, File collection) {
        final var prefix = collection.getName() + "-";
        final var markers = collection
                .listFiles(file -> file.getName().startsWith(prefix) && file.getName().endsWith(BUILDING_SUFFIX));
        if (markers == null) {
            return;
        }
        for (final var marker : markers) {
            final var name = marker.getName();
            final var fieldName = name.substring(prefix.length(), name.length() - BUILDING_SUFFIX.length());
            if (!fieldName.isEmpty()) {
                marked.add(keyOf(projectName, collection.getName(), fieldName));
            }
        }
    }

    private List<File> liveCollections() {
        final var result = new ArrayList<File>();
        final var projects = new File(paths.dbPath()).listFiles(FolderQuarantine::isLiveFolder);
        if (projects == null) {
            return result;
        }
        for (final var project : projects) {
            final var collections = project.listFiles(FolderQuarantine::isLiveFolder);
            if (collections != null) {
                result.addAll(List.of(collections));
            }
        }
        return result;
    }

    private static boolean retain(File marker, File uncleanStop) {
        if (!marker.renameTo(uncleanStop)) {
            return false;
        }
        SyncedWrites.entriesOfLanded(uncleanStop.getAbsoluteFile().getParentFile().toPath(),
                "Retaining the unclean-stop marker " + uncleanStop.getName());
        return true;
    }

    private static void touch(File marker) throws IOException {
        if (!marker.exists()) {
            OwnerOnly.ensureOwnerOnlyFile(marker.toPath());
            Files.writeString(marker.toPath(), Long.toString(System.currentTimeMillis()));
            SyncedWrites.content(marker.toPath());
        }
    }

    private static boolean delete(File marker, String owner) {
        if (marker.exists() && !marker.delete()) {
            logger.warning("Could not clear " + marker.getName() + " for " + owner);
            return false;
        }
        return true;
    }

    private File buildingFile(String projectName, String collName, String fieldName) {
        return new File(paths.collectionFolder(projectName, collName),
                collName + "-" + FilePaths.embedded(fieldName) + BUILDING_SUFFIX);
    }

    private File dirtyFile(String projectName, String collName) {
        return new File(paths.collectionFolder(projectName, collName), collName + DIRTY_SUFFIX);
    }

    private File uncleanStopFile(String projectName, String collName) {
        return new File(paths.collectionFolder(projectName, collName), collName + UNCLEAN_STOP_SUFFIX);
    }

    private static String keyOf(String projectName, String collName, String fieldName) {
        return projectName + Globals.COLL_IDENTIFIER_SEPARATOR + collName + Globals.COLL_IDENTIFIER_SEPARATOR
                + fieldName;
    }
}
