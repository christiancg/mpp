package org.ccg.mpp.ops.admin;

import org.ccg.mpp.bckg_ops.ScheduleRegistry;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.log.Logger;
import org.ccg.mpp.ops.CompiledProcedureCache;
import org.ccg.mpp.ops.DroppedCollections;

public final class LeftoverFolders {
    private static final Logger logger = Logger.logFor(LeftoverFolders.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final CompiledProcedureCache compiledProcedures = IocContainer.get(CompiledProcedureCache.class);
    private static final ScheduleRegistry scheduleRegistry = IocContainer.get(ScheduleRegistry.class);

    private LeftoverFolders() {
    }

    public static boolean moveAsideUnregisteredCollection(String dbName, String collName) {
        if (cache.getAdminCollectionEntry(dbName, collName) != null
                || !fs.folderQuarantine().holdsLeftoverCollection(dbName, collName)) {
            return true;
        }
        final var moved = fs.folderQuarantine().moveCollectionAside(dbName, collName, 0);
        DroppedCollections.forgetCollection(dbName, collName);
        logOutcome("collection " + dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName, moved);
        return moved;
    }

    public static boolean leftoverDatabaseStays(String dbName) {
        if (cache.getAdminDbEntry(dbName) != null || !fs.folderQuarantine().holdsLeftoverDatabase(dbName)) {
            return false;
        }
        final var moved = fs.folderQuarantine().moveDatabaseAside(dbName);
        DroppedCollections.forgetDatabase(dbName);
        compiledProcedures.invalidateDatabase(dbName);
        scheduleRegistry.removeDatabase(dbName);
        logOutcome("database " + dbName, moved);
        return !moved;
    }

    private static void logOutcome(String what, boolean moved) {
        if (moved) {
            logger.warning("Moved aside the leftover folder of " + what
                    + ": it holds data but is not registered, so it belongs to a dropped incarnation and is never"
                    + " adopted by a new one.");
        } else {
            logger.error("Could not move aside the leftover folder of " + what
                    + ": it holds data but is not registered, so it is not adopted and the create is refused.");
        }
    }
}
