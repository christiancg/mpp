package org.ccg.mpp.fs;

import java.io.File;
import org.ccg.mpp.config.Globals;

public final class FolderQuarantine {
    private final FilePaths paths;

    FolderQuarantine(FilePaths paths) {
        this.paths = paths;
    }

    static boolean isLiveFolder(File file) {
        return file.isDirectory() && !file.getName().contains(Globals.QUARANTINE_INFIX);
    }

    public boolean moveCollectionAside(String projectName, String collectionName, long incarnation) {
        final var collectionFolder = paths.collectionFolder(projectName, collectionName);
        if (!collectionFolder.exists()) {
            return false;
        }
        return moveAside(collectionFolder, collectionName + Globals.QUARANTINE_INFIX + incarnation + "-");
    }

    public boolean moveProjectAside(String projectName) {
        final var projectFolder = paths.rawProjectFolder(projectName);
        if (!projectFolder.exists()) {
            return false;
        }
        return moveAside(projectFolder, projectName + Globals.QUARANTINE_INFIX);
    }

    public boolean holdsLeftoverCollection(String projectName, String collectionName) {
        final var files = paths.collectionFolder(projectName, collectionName).listFiles();
        if (files == null) {
            return false;
        }
        for (final var file : files) {
            if (file.isDirectory() || file.length() > 0) {
                return true;
            }
        }
        return false;
    }

    public boolean holdsLeftoverProject(String projectName) {
        final var files = paths.rawProjectFolder(projectName).listFiles();
        return files != null && files.length > 0;
    }

    private static boolean moveAside(File folder, String targetPrefix) {
        PageLengths.forgetUnder(folder);
        final var stamp = targetPrefix + System.currentTimeMillis();
        var target = new File(folder.getParentFile(), stamp);
        for (var attempt = 1; target.exists(); attempt++) {
            target = new File(folder.getParentFile(), stamp + "-" + attempt);
        }
        if (!folder.renameTo(target)) {
            return false;
        }
        WriteIntentJournal.forgetUnder(folder);
        WriteAheadLog.forgetUnder(folder);
        PkIndexStore.forgetUnder(folder);
        TombstoneStore.forgetUnder(folder);
        FieldIndexLog.forgetUnder(folder);
        SyncedWrites.entriesOfLanded(target.getAbsoluteFile().getParentFile().toPath(),
                "Moving " + folder.getName() + " aside");
        return true;
    }
}
