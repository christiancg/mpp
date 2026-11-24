package org.ccg.mpp.fs;

import java.io.File;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.NameMemo;
import org.ccg.mpp.ex.InvalidPathSegmentException;

// Every builder funnels through projectFolder, making this the only place the reserved logical db
// name ADMIN_PAGES_PROJECT_NAME is translated to the physical admin/pages subfolder.
final class FilePaths {
    static final int MAX_REMEMBERED_PREFIXES = 8192;
    private final NameMemo<String> prefixes = new NameMemo<>(MAX_REMEMBERED_PREFIXES);
    private final NameMemo<File> pkIndexFiles = new NameMemo<>(MAX_REMEMBERED_PREFIXES);
    private String dbPath;

    void useDbPath(String dbPath) {
        this.dbPath = new File(dbPath).getAbsolutePath();
        prefixes.clear();
        pkIndexFiles.clear();
    }

    void forgetCollection(String projectName, String collectionName) {
        prefixes.forget(projectName, collectionName);
        pkIndexFiles.forget(projectName, collectionName);
    }

    void forgetProject(String projectName) {
        prefixes.forgetAll(projectName);
        pkIndexFiles.forgetAll(projectName);
    }

    int rememberedPrefixCount() {
        return prefixes.size();
    }

    String dbPath() {
        return dbPath;
    }

    File projectFolder(String projectName) {
        return new File(dbPath + Globals.FILE_SEPARATOR + resolveProjectPathSegment(segment(projectName)));
    }

    File rawProjectFolder(String projectName) {
        return new File(dbPath + Globals.FILE_SEPARATOR + segment(projectName));
    }

    File collectionFolder(String projectName, String collectionName) {
        return new File(projectFolder(projectName).getPath() + Globals.FILE_SEPARATOR + segment(collectionName));
    }

    File collectionPage(String projectName, String collectionName, long page) {
        return new File(collectionPrefix(projectName, collectionName) + Globals.FILE_PAGE_SEPARATOR + page
                + Globals.DB_FILE_EXTENSION);
    }

    File snapshotsFolder() {
        return new File(dbPath + Globals.FILE_SEPARATOR + Globals.SNAPSHOT_FOLDER_NAME);
    }

    File snapshotFolder(String snapshotId) {
        return new File(snapshotsFolder(), segment(snapshotId));
    }

    File snapshotImages(String snapshotId, String collectionName) {
        return new File(snapshotFolder(snapshotId), segment(collectionName) + Globals.SNAPSHOT_IMAGES_EXTENSION);
    }

    File writeAheadLog(String projectName, String collectionName) {
        return new File(collectionPrefix(projectName, collectionName) + Globals.WAL_EXTENSION);
    }

    File intentLog(String projectName, String collectionName) {
        return new File(collectionPrefix(projectName, collectionName) + Globals.INTENT_LOG_EXTENSION);
    }

    File vacuumFile(String projectName, String collectionName, long page) {
        return new File(collectionPrefix(projectName, collectionName) + Globals.FILE_PAGE_SEPARATOR + page
                + Globals.VACUUM_FILE_EXTENSION);
    }

    File indexFile(String projectName, String collectionName, String fieldName, String indexType) {
        if (isReservedFileName(fieldName)) {
            return null;
        }
        return new File(collectionPrefix(projectName, collectionName) + Globals.INDEX_FILE_NAME_SEPARATOR
                + embedded(fieldName) + Globals.INDEX_FILE_NAME_SEPARATOR + indexType + Globals.INDEX_FILE_EXTENSION);
    }

    static File logFileOf(File indexFile) {
        return new File(indexFile.getPath() + Globals.INDEX_LOG_SUFFIX);
    }

    private static boolean isReservedFileName(String fieldName) {
        return Globals.PK_FIELD.equals(fieldName) || Globals.TOMBSTONE_FILE_NAME.equals(fieldName);
    }

    File pkIndexFile(String projectName, String collectionName) {
        final var hit = pkIndexFiles.get(projectName, collectionName);
        if (hit != null) {
            return hit;
        }
        return pkIndexFiles.remember(projectName, collectionName, new File(collectionPrefix(projectName, collectionName)
                + Globals.INDEX_FILE_NAME_SEPARATOR + Globals.PK_INDEX_FILE_NAME + Globals.INDEX_FILE_EXTENSION));
    }

    File adminTombstoneFile() {
        return new File(dbPath + Globals.FILE_SEPARATOR + Globals.CLUSTER_FOLDER + Globals.FILE_SEPARATOR
                + Globals.CLUSTER_ADMIN_TOMBSTONES_FILE);
    }

    File formatStampFile() {
        return new File(dbPath + Globals.FILE_SEPARATOR + Globals.FORMAT_STAMP_FILE);
    }

    File nodeIdFile() {
        return new File(dbPath + Globals.FILE_SEPARATOR + Globals.CLUSTER_FOLDER + Globals.FILE_SEPARATOR
                + Globals.CLUSTER_NODE_ID_FILE);
    }

    File tombstoneFile(String projectName, String collectionName) {
        return new File(collectionPrefix(projectName, collectionName) + Globals.INDEX_FILE_NAME_SEPARATOR
                + Globals.TOMBSTONE_FILE_NAME + Globals.INDEX_FILE_EXTENSION);
    }

    File schemaFile(String projectName, String collectionName) {
        return new File(collectionPrefix(projectName, collectionName) + Globals.INDEX_FILE_NAME_SEPARATOR
                + Globals.SCHEMA_FILE_NAME + Globals.SCHEMA_FILE_EXTENSION);
    }

    File triggersFile(String projectName, String collectionName) {
        return new File(collectionPrefix(projectName, collectionName) + Globals.INDEX_FILE_NAME_SEPARATOR
                + Globals.TRIGGERS_FILE_NAME + Globals.TRIGGERS_FILE_EXTENSION);
    }

    File adminPagesFolder() {
        return new File(dbPath + Globals.FILE_SEPARATOR + Globals.ADMIN_PROJECT_NAME + Globals.FILE_SEPARATOR
                + Globals.ADMIN_PAGES_FOLDER);
    }

    File proceduresFolder(String projectName) {
        return new File(projectFolder(projectName).getPath() + Globals.FILE_SEPARATOR + Globals.PROCEDURES_FOLDER);
    }

    // The name becomes a path segment here and nowhere else; RequestValidator has already matched it
    // against the collection-name rule, so a separator or a '..' segment is unrepresentable.
    File procedureFile(String projectName, String name) {
        return new File(proceduresFolder(projectName).getPath() + Globals.FILE_SEPARATOR + segment(name)
                + Globals.PROCEDURE_FILE_EXTENSION);
    }

    File schedulesFolder(String projectName) {
        return new File(projectFolder(projectName).getPath() + Globals.FILE_SEPARATOR + Globals.SCHEDULES_FOLDER);
    }

    File scheduleFile(String projectName, String name) {
        return new File(schedulesFolder(projectName).getPath() + Globals.FILE_SEPARATOR + segment(name)
                + Globals.SCHEDULE_FILE_EXTENSION);
    }

    File entrypointsFolder(String projectName) {
        return new File(projectFolder(projectName).getPath() + Globals.FILE_SEPARATOR + Globals.ENTRYPOINTS_FOLDER);
    }

    File entrypointFile(String projectName, String name) {
        return new File(entrypointsFolder(projectName).getPath() + Globals.FILE_SEPARATOR + segment(name)
                + Globals.ENTRYPOINT_FILE_EXTENSION);
    }

    File middlewaresFolder(String projectName) {
        return new File(projectFolder(projectName).getPath() + Globals.FILE_SEPARATOR + Globals.MIDDLEWARES_FOLDER);
    }

    File middlewareFile(String projectName, String name) {
        return new File(middlewaresFolder(projectName).getPath() + Globals.FILE_SEPARATOR + segment(name)
                + Globals.MIDDLEWARE_FILE_EXTENSION);
    }

    private String collectionPrefix(String projectName, String collectionName) {
        final var hit = prefixes.get(projectName, collectionName);
        if (hit != null) {
            return hit;
        }
        final var prefix = collectionFolder(projectName, collectionName).getPath() + Globals.FILE_SEPARATOR
                + collectionName;
        return prefixes.remember(projectName, collectionName, prefix);
    }

    private static String segment(String name) {
        if (name == null || name.isEmpty() || ".".equals(name) || "..".equals(name) || hasSeparator(name)) {
            throw new InvalidPathSegmentException();
        }
        return name;
    }

    static String embedded(String name) {
        if (name == null || name.isEmpty() || hasSeparator(name)) {
            throw new InvalidPathSegmentException();
        }
        return name;
    }

    private static boolean hasSeparator(String name) {
        for (var i = 0; i < name.length(); i++) {
            final var c = name.charAt(i);
            if (c == '/' || c == '\\' || c == '\0') {
                return true;
            }
        }
        return false;
    }

    private String resolveProjectPathSegment(String projectName) {
        if (Globals.ADMIN_PAGES_PROJECT_NAME.equals(projectName)) {
            return Globals.ADMIN_PROJECT_NAME + Globals.FILE_SEPARATOR + Globals.ADMIN_PAGES_FOLDER;
        }
        return projectName;
    }
}
