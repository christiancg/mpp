package org.ccg.mpp.cluster.admin;

import java.io.IOException;
import org.ccg.mpp.bckg_ops.ScheduleRegistry;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.cluster.ClusterConfig;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.ejson.EJson;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.log.Logger;
import org.ccg.mpp.ops.CompiledProcedureCache;
import org.ccg.mpp.ops.SchemaPatternCaps;
import org.ccg.mpp.ops.admin.AdminRecordKey;
import org.ccg.mpp.ops.admin.AdminStamp;

final class DefinitionRecordApplier {
    private static final Logger logger = Logger.logFor(DefinitionRecordApplier.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final EJson eJson = IocContainer.get(EJson.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private static final CompiledProcedureCache compiledProcedures = IocContainer.get(CompiledProcedureCache.class);
    private static final ScheduleRegistry scheduleRegistry = IocContainer.get(ScheduleRegistry.class);

    private DefinitionRecordApplier() {
    }

    private interface FileChange {
        void apply() throws Exception;
    }

    static boolean install(AdminRecord record) throws Exception {
        final var key = record.key();
        return underLock(key, () -> {
            if (!AdminRecords.stillOutranks(record)) {
                return;
            }
            writeFile(record);
            invalidate(key);
        });
    }

    static boolean remove(AdminRecord tombstone) throws Exception {
        final var key = tombstone.key();
        return underLock(key, () -> {
            AdminStamp.tombstoneAt(key, tombstone.version());
            if (!tombstone.outranks(AdminRecords.live(key))) {
                return;
            }
            deleteFile(key);
            invalidate(key);
        });
    }

    private static void writeFile(AdminRecord record) throws IOException {
        final var key = record.key();
        final var json = eJson.toJson(record.body());
        switch (key.kind()) {
            case SCHEMA -> {
                fs.metadataFiles().writeCollectionSchema(key.projectName(), key.name(), json);
                SchemaPatternCaps.warnWhenOver(
                        "Stored the replicated schema of "
                                + Cache.getCollectionIdentifier(key.projectName(), key.name()),
                        AdminStamp.unwrappedSchema(record.body()));
            }
            case TRIGGERS -> fs.metadataFiles().writeTriggers(key.projectName(), key.name(), json);
            case PROCEDURE -> fs.metadataFiles().writeProcedure(key.projectName(), key.name(), json);
            default -> fs.metadataFiles().writeSchedule(key.projectName(), key.name(), json);
        }
    }

    private static void deleteFile(AdminRecordKey key) throws IOException {
        switch (key.kind()) {
            case SCHEMA -> fs.metadataFiles().deleteCollectionSchema(key.projectName(), key.name());
            case TRIGGERS -> fs.metadataFiles().deleteTriggers(key.projectName(), key.name());
            case PROCEDURE -> fs.metadataFiles().deleteProcedure(key.projectName(), key.name());
            default -> fs.metadataFiles().deleteSchedule(key.projectName(), key.name());
        }
    }

    private static void invalidate(AdminRecordKey key) {
        switch (key.kind()) {
            case SCHEMA -> cache.removeCollectionSchema(key.projectName(), key.name());
            case TRIGGERS -> cache.removeTriggers(key.projectName(), key.name());
            case PROCEDURE -> {
                cache.removeProcedure(key.projectName(), key.name());
                compiledProcedures.invalidateProcedure(key.projectName(), key.name());
            }
            default -> {
                cache.removeSchedule(key.projectName(), key.name());
                scheduleRegistry.reload(key.projectName());
            }
        }
    }

    private static String lockName(AdminRecordKey key) {
        return switch (key.kind()) {
            case PROCEDURE -> Globals.PROCEDURES_FOLDER;
            case SCHEDULE -> Globals.SCHEDULES_FOLDER;
            default -> key.name();
        };
    }

    private static boolean underLock(AdminRecordKey key, FileChange change) throws Exception {
        final var waitMillis = clusterConfig.replicationAckTimeoutMs();
        final var lockName = lockName(key);
        if (!locks.tryLockWrite(key.projectName(), lockName, waitMillis)) {
            logger.warning("Skipping " + key.id() + ": its lock stayed held for " + waitMillis
                    + "ms. The next round retries it.");
            return false;
        }
        try {
            change.apply();
            return true;
        } finally {
            locks.release(key.projectName(), lockName);
        }
    }
}
