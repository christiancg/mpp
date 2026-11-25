package org.ccg.mpp;

import java.io.IOException;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.config.ServerVersion;
import org.ccg.mpp.ex.IncompatibleStorageFormatException;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.fs.FormatStamp;
import org.ccg.mpp.fs.FormatStampFile;
import org.ccg.mpp.fs.MalformedFormatStampException;
import org.ccg.mpp.fs.ProjectFileStore;
import org.ccg.mpp.log.Logger;

public final class StorageFormatGuard {
    private static final Logger logger = Logger.logFor(StorageFormatGuard.class);
    private static final String UNSTAMPED_WRITER = "a release before 0.1.0";
    private static final String RELEASE_PREFIX = "mpp ";

    private StorageFormatGuard() {
    }

    public static void refuseIncompatibleFolder(FileSystem fs) throws IOException {
        refuseIncompatible(fs.formatStamp(), Globals.STORAGE_FORMAT_VERSION);
    }

    public static void refuseIncompatibleFiles(ProjectFileStore files) throws IOException {
        refuseIncompatible(files.formatStamp(), Globals.FILES_FORMAT_VERSION);
    }

    private static void refuseIncompatible(FormatStampFile file, int expected) throws IOException {
        final var stamp = readStamp(file);
        if (stamp == null && !file.holdsData()) {
            return;
        }
        final var found = stamp == null
                ? new FormatStamp(Globals.UNSTAMPED_STORAGE_FORMAT_VERSION, UNSTAMPED_WRITER)
                : stamp;
        if (found.formatVersion() > expected) {
            throw new IncompatibleStorageFormatException(newerFormat(file, found, expected));
        }
        if (found.formatVersion() < expected) {
            throw new IncompatibleStorageFormatException(olderFormat(file, found, expected));
        }
    }

    public static void stampIfUnstamped(FileSystem fs) throws IOException {
        stampIfUnstamped(fs.formatStamp(), FormatStamp.current());
    }

    public static void stampFilesIfUnstamped(ProjectFileStore files) throws IOException {
        stampIfUnstamped(files.formatStamp(), new FormatStamp(Globals.FILES_FORMAT_VERSION, ServerVersion.current()));
    }

    private static void stampIfUnstamped(FormatStampFile file, FormatStamp current) throws IOException {
        if (file.read() == null) {
            file.write(current);
            logger.info("Stamped " + file.path().toAbsolutePath() + " with storage format " + current.formatVersion());
        }
    }

    private static FormatStamp readStamp(FormatStampFile file) throws IncompatibleStorageFormatException {
        try {
            return file.read();
        } catch (MalformedFormatStampException e) {
            throw new IncompatibleStorageFormatException(e.getMessage(), e);
        } catch (IOException e) {
            throw new IncompatibleStorageFormatException("Could not read the storage format stamp at "
                    + file.path().toAbsolutePath() + ": " + e.getMessage(), e);
        }
    }

    private static String holds(FormatStampFile file, FormatStamp stamp, int expected) {
        return file.path().toAbsolutePath() + " says this folder holds storage format " + stamp.formatVersion()
                + " (written by " + writerOf(stamp) + "), but this release (" + ServerVersion.current()
                + ") reads only format " + expected + ". ";
    }

    private static String writerOf(FormatStamp stamp) {
        return UNSTAMPED_WRITER.equals(stamp.writtenBy()) ? UNSTAMPED_WRITER : RELEASE_PREFIX + stamp.writtenBy();
    }

    private static String newerFormat(FormatStampFile file, FormatStamp stamp, int expected) {
        return holds(file, stamp, expected) + "Run " + RELEASE_PREFIX + stamp.writtenBy() + " or newer on this folder.";
    }

    private static String olderFormat(FormatStampFile file, FormatStamp stamp, int expected) {
        return holds(file, stamp, expected) + "Dump it with " + writerOf(stamp)
                + " (java -jar mpp.jar --dump ... > backup.jsonl), then start this release on an empty filePath"
                + " and restore it (--restore < backup.jsonl).";
    }
}
