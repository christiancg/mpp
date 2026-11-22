package org.ccg.mpp.cluster.admin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.DbEntry;
import org.ccg.mpp.data.PkIndexEntry;
import org.ccg.mpp.data.admin.AdminCollEntry;
import org.ccg.mpp.data.admin.AdminProjectEntry;
import org.ccg.mpp.data.admin.AdminUserEntry;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.log.Logger;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.admin.AdminRecordKey;
import org.ccg.mpp.ops.admin.AdminStamp;
import org.ccg.mpp.ops.admin.StoredDefinitions;

public final class AdminRecords {
    private static final Logger logger = Logger.logFor(AdminRecords.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);

    private AdminRecords() {
    }

    public static List<AdminRecord> all() throws IOException {
        return withTombstonesIn(AdminStamp.tombstones(), null, false);
    }

    public static List<AdminRecord> snapshotFor(int peerBucketCount, List<String> peerBuckets) throws IOException {
        final var tombstones = AdminStamp.tombstones();
        if (!AdminTombstoneDigest.isValid(peerBucketCount, peerBuckets)) {
            return withTombstonesIn(tombstones, null, true);
        }
        final var differing = AdminTombstoneDigest.of(tombstones, peerBucketCount).differingFrom(peerBuckets);
        return withTombstonesIn(tombstones,
                key -> differing.contains(AdminTombstoneDigest.bucketOf(key, peerBucketCount)), true);
    }

    private static List<AdminRecord> withTombstonesIn(Map<String, Long> tombstones, Predicate<String> shipped,
            boolean withSuperseded) {
        final var keys = liveKeys();
        for (final var id : tombstones.keySet()) {
            if (shipped == null || shipped.test(id)) {
                keys.add(AdminRecordKey.parse(id));
            }
        }
        final var records = new ArrayList<AdminRecord>();
        for (final var key : keys.stream().distinct().toList()) {
            final var record = current(key, tombstones);
            final var tombstoneShipped = shipped == null || shipped.test(key.id());
            if (record != null && (!record.isTombstone() || tombstoneShipped)) {
                records.add(record);
            }
            final var superseded = tombstones.get(key.id());
            if (withSuperseded && tombstoneShipped && superseded != null && record != null && !record.isTombstone()) {
                records.add(AdminRecord.tombstone(key, superseded));
            }
        }
        return records;
    }

    private static List<AdminRecordKey> liveKeys() {
        final var keys = new ArrayList<AdminRecordKey>();
        for (final var projectName : cache.getUserProjectNames()) {
            keys.add(AdminRecordKey.project(projectName));
            for (final var collName : cache.getCollectionNamesForProject(projectName)) {
                keys.add(AdminRecordKey.collection(projectName, collName));
                keys.add(AdminRecordKey.schema(projectName, collName));
                keys.add(AdminRecordKey.triggers(projectName, collName));
            }
            listed(projectName, Globals.PROCEDURES_FOLDER, () -> fs.metadataFiles().listProcedureNames(projectName))
                    .forEach(name -> keys.add(AdminRecordKey.procedure(projectName, name)));
            listed(projectName, Globals.SCHEDULES_FOLDER, () -> fs.metadataFiles().listScheduleNames(projectName))
                    .forEach(name -> keys.add(AdminRecordKey.schedule(projectName, name)));
            listed(projectName, Globals.ENTRYPOINTS_FOLDER, () -> fs.metadataFiles().listEntrypointNames(projectName))
                    .forEach(name -> keys.add(AdminRecordKey.entrypoint(projectName, name)));
        }
        for (final var user : cache.getAllAdminUserEntries()) {
            keys.add(AdminRecordKey.user(user.get_id()));
        }
        return keys;
    }

    public static List<AdminRecord> of(List<AdminRecordKey> keys) throws IOException {
        final var tombstones = AdminStamp.tombstones();
        final var records = new ArrayList<AdminRecord>();
        for (final var key : keys) {
            final var record = current(key, tombstones);
            if (record != null) {
                records.add(record);
            }
        }
        return records;
    }

    public static boolean stillOutranks(AdminRecord record) throws IOException {
        return record.outranks(current(record.key(), AdminStamp.tombstones()));
    }

    public static List<AdminRecord> shadowed() throws IOException {
        final var tombstones = AdminStamp.tombstones();
        final var shadowed = new ArrayList<AdminRecord>();
        for (final var key : liveKeys().stream().distinct().toList()) {
            final var tombstoneVersion = tombstones.get(key.id());
            if (tombstoneVersion == null) {
                continue;
            }
            final var live = live(key);
            if (live != null && tombstoneVersion >= live.version()) {
                shadowed.add(AdminRecord.tombstone(key, tombstoneVersion));
            }
        }
        return shadowed;
    }

    public static AdminRecord current(AdminRecordKey key, Map<String, Long> tombstones) {
        final var live = live(key);
        final var tombstoneVersion = tombstones.getOrDefault(key.id(), 0L);
        if (tombstones.containsKey(key.id()) && (live == null || tombstoneVersion >= live.version())) {
            return AdminRecord.tombstone(key, tombstoneVersion);
        }
        return live;
    }

    public static AdminRecord live(AdminRecordKey key) {
        try {
            return switch (key.kind()) {
                case PROJECT -> row(key, cache.getAdminProjectEntry(key.projectName()),
                        cache.getPkIndexAdminProjectEntry(key.projectName()));
                case COLLECTION -> collection(key);
                case USER -> row(key, cache.getAdminUserEntry(key.name()), cache.getPkIndexAdminUserEntry(key.name()));
                case SCHEMA -> stored(key, StoredDefinitions.schema(key.projectName(), key.name()));
                case TRIGGERS -> stored(key, StoredDefinitions.triggers(key.projectName(), key.name()));
                case PROCEDURE -> stored(key, StoredDefinitions.procedure(key.projectName(), key.name()));
                case SCHEDULE -> stored(key, StoredDefinitions.schedule(key.projectName(), key.name()));
                case ENTRYPOINT -> stored(key, StoredDefinitions.entrypoint(key.projectName(), key.name()));
            };
        } catch (IOException | RuntimeException unreadable) {
            logger.warning("Leaving " + key.id() + " out of the admin records: it could not be read: "
                    + unreadable.getMessage());
            return null;
        }
    }

    private static AdminRecord collection(AdminRecordKey key) {
        final var entry = cache.getAdminCollectionEntry(key.projectName(), key.name());
        if (entry == null) {
            return null;
        }
        final var body = entry.getData().deepCopy();
        body.addProperty(Globals.PK_FIELD, entry.get_id());
        return new AdminRecord(key,
                AdminOperationHelper.versionOf(
                        cache.getPkIndexAdminCollEntry(Cache.getCollectionIdentifier(key.projectName(), key.name()))),
                body);
    }

    private static AdminRecord row(AdminRecordKey key, DbEntry entry, PkIndexEntry pk) {
        if (entry == null) {
            return null;
        }
        final var body = entry.getData().deepCopy();
        body.addProperty(Globals.PK_FIELD, entry.get_id());
        return new AdminRecord(key, AdminOperationHelper.versionOf(pk), body);
    }

    private static AdminRecord stored(AdminRecordKey key, JsonObject body) {
        return body == null ? null : new AdminRecord(key, AdminStamp.versionOf(body), body);
    }

    private interface Listing {
        List<String> names() throws IOException;
    }

    private static List<String> listed(String projectName, String folder, Listing listing) {
        try {
            return listing.names();
        } catch (IOException unlistable) {
            logger.warning("Leaving the " + folder + " of " + projectName + " out of the admin records: it could not be"
                    + " listed: " + unlistable.getMessage());
            return List.of();
        }
    }

    public static void stampUnversioned() {
        try {
            for (final var record : all()) {
                if (!record.isTombstone() && record.version() == 0) {
                    stampUnversioned(record);
                }
            }
        } catch (Exception failure) {
            logger.error(
                    "Could not version the admin records written before admin records were versioned; a peer's"
                            + " newer-looking copy, such as a fresh node's bootstrap admin, would replace them",
                    failure);
        }
    }

    private static void stampUnversioned(AdminRecord record) throws Exception {
        final var key = record.key();
        final var body = record.body();
        switch (key.kind()) {
            case PROJECT -> AdminOperationHelper.saveProjectEntry(AdminProjectEntry.fromJsonObject(body));
            case COLLECTION -> AdminOperationHelper.saveCollectionEntry(AdminCollEntry.fromJsonObject(body));
            case USER -> AdminOperationHelper.saveUserEntry(AdminUserEntry.fromJsonObject(body));
            case SCHEMA ->
                StoredDefinitions.writeSchema(key.projectName(), key.name(), AdminStamp.unwrappedSchema(body));
            case TRIGGERS -> StoredDefinitions.writeTriggers(key.projectName(), key.name(), body);
            case PROCEDURE -> StoredDefinitions.writeProcedure(key.projectName(), key.name(), body);
            case SCHEDULE -> StoredDefinitions.writeSchedule(key.projectName(), key.name(), body);
            default -> StoredDefinitions.writeEntrypoint(key.projectName(), key.name(), body);
        }
    }
}
