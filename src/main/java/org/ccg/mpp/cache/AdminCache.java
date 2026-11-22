package org.ccg.mpp.cache;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.DbEntry;
import org.ccg.mpp.data.PkIndexEntry;
import org.ccg.mpp.data.ProcedureDefinition;
import org.ccg.mpp.data.ScheduleDefinition;
import org.ccg.mpp.data.TriggerDefinition;
import org.ccg.mpp.data.admin.AdminCollEntry;
import org.ccg.mpp.data.admin.AdminPageEntry;
import org.ccg.mpp.data.admin.AdminProjectEntry;
import org.ccg.mpp.data.admin.AdminUserEntry;
import org.ccg.mpp.ejson.EJson;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ex.MetadataReadException;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.fs.VacuumedPage;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.log.Logger;
import org.ccg.mpp.ops.SchemaPatternCaps;
import org.ccg.mpp.ops.admin.AdminStamp;

public class AdminCache {
    private static final Logger logger = Logger.logFor(AdminCache.class);
    private static final String PROCEDURE_MISS_PREFIX = "p" + Globals.COLL_IDENTIFIER_SEPARATOR;
    private static final String SCHEMA_MISS_PREFIX = "s" + Globals.COLL_IDENTIFIER_SEPARATOR;
    private static final String SCHEDULE_MISS_PREFIX = "k" + Globals.COLL_IDENTIFIER_SEPARATOR;
    private final Configuration configuration = Configuration.getInstance();
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final EJson eJson = IocContainer.get(EJson.class);
    private final Map<String, AdminProjectEntry> projects = new ConcurrentHashMap<>();
    private final Map<String, AdminCollEntry> collections = new ConcurrentHashMap<>();
    private final Map<String, AdminUserEntry> users = new ConcurrentHashMap<>();
    private final AdminPageCache pageCache = new AdminPageCache();
    private final Map<String, PkIndexEntry> projectsPkIndex = new ConcurrentHashMap<>();
    private final Map<String, PkIndexEntry> collectionsPkIndex = new ConcurrentHashMap<>();
    private final Map<String, PkIndexEntry> usersPkIndex = new ConcurrentHashMap<>();
    private final Map<String, PkIndexEntry> collectionUsagePkIndex = new ConcurrentHashMap<>();
    private final Map<String, PkIndexEntry> transactionsPkIndex = new ConcurrentHashMap<>();
    private final NavigableMap<String, PkIndexEntry> triggerRunsPkIndex = new ConcurrentSkipListMap<>();
    // Evictable because disk is authoritative, unlike every other map here. Misses are kept in a separate
    // cache so a caller naming thousands of nonexistent procedures cannot evict the ones in use.
    private final BoundedLruCache<JsonObject> collectionSchemas = new BoundedLruCache<>(Integer.MAX_VALUE,
            configuration.getMetadataCacheMaxBytes() / 3, schema -> (long) eJson.toJson(schema).length() * 2L);
    private final BoundedLruCache<ProcedureDefinition> procedures = new BoundedLruCache<>(Integer.MAX_VALUE,
            configuration.getMetadataCacheMaxBytes() / 3,
            definition -> (long) definition.getSource().length() * 2L + 512L);
    private final GenerationGuard triggerGeneration = new GenerationGuard();
    private final BoundedLruCache<List<TriggerDefinition>> triggers = new BoundedLruCache<>(
            configuration.getMetadataCacheMaxEntries(), 0L, definitions -> definitions.size() * 512L + 128L);
    private final BoundedLruCache<ScheduleDefinition> schedules = new BoundedLruCache<>(Integer.MAX_VALUE,
            configuration.getMetadataCacheMaxBytes() / 3,
            definition -> (long) eJson.toJson(definition.toJsonObject()).length() * 2L);
    private final PartitionedMissCache metadataMisses = new PartitionedMissCache(
            configuration.getMetadataCacheMaxEntries());
    private final DefinitionCache<JsonObject> schemaCache = new DefinitionCache<>(() -> collectionSchemas,
            () -> metadataMisses, SCHEMA_MISS_PREFIX, this::loadSchemaUncached);
    private final DefinitionCache<ProcedureDefinition> procedureCache = new DefinitionCache<>(() -> procedures,
            () -> metadataMisses, PROCEDURE_MISS_PREFIX, this::loadProcedureUncached);
    private final DefinitionCache<ScheduleDefinition> scheduleCache = new DefinitionCache<>(() -> schedules,
            () -> metadataMisses, SCHEDULE_MISS_PREFIX, this::loadScheduleUncached);
    private final EntrypointDefinitions entrypoints = new EntrypointDefinitions(
            configuration.getMetadataCacheMaxBytes() / 3, () -> metadataMisses);

    public void loadAdminData() throws IOException {
        for (var collName : Globals.ADMIN_COLLECTION_NAMES) {
            pageCache.loadAdminPagesForCollection(Globals.ADMIN_PROJECT_NAME, collName);
        }
        loadPkIndexInto(Globals.ADMIN_COLLECTION_USAGE_NAME, collectionUsagePkIndex);
        loadPkIndexInto(Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME, transactionsPkIndex);
        loadPkIndexInto(Globals.ADMIN_TRIGGER_RUNS_COLLECTION_NAME, triggerRunsPkIndex);
        loadPkIndexInto(Globals.ADMIN_PROJECTS_COLLECTION_NAME, projectsPkIndex);
        loadPkIndexInto(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME, collectionsPkIndex);
        loadPkIndexInto(Globals.ADMIN_USERS_COLLECTION_NAME, usersPkIndex);
        loadAdminCollection(Globals.ADMIN_PROJECTS_COLLECTION_NAME, projectsPkIndex, AdminProjectEntry::fromJsonObject,
                projects);
        loadAdminCollection(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME, collectionsPkIndex,
                AdminCollEntry::fromJsonObject, collections);
        loadAdminCollection(Globals.ADMIN_USERS_COLLECTION_NAME, usersPkIndex, AdminUserEntry::fromJsonObject, users);
        for (var collEntry : collections.values()) {
            final var parts = collEntry.get_id().split(Globals.COLL_IDENTIFIER_SEPARATOR_REGEX);
            if (parts.length < 2)
                continue;
            pageCache.loadAdminPagesForCollection(parts[0], parts[1]);
        }
    }

    private <V> void loadAdminEntries(Map<String, DbEntry> source, String adminCollName,
            java.util.function.Function<JsonObject, V> mapper, Map<String, V> target) {
        for (var entry : source.entrySet()) {
            try {
                target.put(entry.getKey(), mapper.apply(entry.getValue().getData()));
            } catch (Exception e) {
                logger.warning("Skipping malformed admin entry '" + entry.getKey() + "' in " + adminCollName + ": "
                        + e.getMessage());
            }
        }
    }

    private void loadPkIndexInto(String collName, Map<String, PkIndexEntry> target) throws IOException {
        target.putAll(fs.readWholePkIndexFile(Globals.ADMIN_PROJECT_NAME, collName).stream()
                .collect(Collectors.toConcurrentMap(PkIndexEntry::getValue, indexEntry -> indexEntry)));
    }

    private <T extends DbEntry> void loadAdminCollection(String collName, Map<String, PkIndexEntry> pkIndex,
            Function<JsonObject, T> factory, Map<String, T> target) throws IOException {
        if (pkIndex.isEmpty()) {
            return;
        }
        loadAdminEntries(readWholeAdminCollection(collName), collName, factory, target);
    }

    private Map<String, DbEntry> readWholeAdminCollection(String collName) throws IOException {
        final var result = new HashMap<String, DbEntry>();
        try (var pagesStream = fs.streamPages(Globals.ADMIN_PROJECT_NAME, collName)) {
            pagesStream.forEach(result::putAll);
        }
        return result;
    }

    public List<AdminPageEntry> getAdminPageEntries(String projectName, String collName) {
        return pageCache.getAdminPageEntries(projectName, collName);
    }

    public AdminPageEntry getAdminPageEntry(String projectName, String collName, long page) {
        return pageCache.getAdminPageEntry(projectName, collName, page);
    }

    public void addAdminPageEntries(String projectName, String collName, AdminPageEntry adminPageEntry) {
        pageCache.addAdminPageEntries(projectName, collName, adminPageEntry);
    }

    public void addAdminPageEntries(String projectName, String collName, List<AdminPageEntry> adminPageEntries) {
        pageCache.addAdminPageEntries(projectName, collName, adminPageEntries);
    }

    public void updatePageSizeInMemory(String projectName, String collName, long page, long bytesDelta) {
        pageCache.updatePageSizeInMemory(projectName, collName, page, bytesDelta);
    }

    public void updatePageSizeInMemory(String projectName, String collName, long page, long bytesDelta,
            int addedEntries) {
        pageCache.updatePageSizeInMemory(projectName, collName, page, bytesDelta, addedEntries);
    }

    public void updatePageSizeForUpdateInMemory(String projectName, String collName, long page, long bytesDelta) {
        pageCache.updatePageSizeForUpdateInMemory(projectName, collName, page, bytesDelta);
    }

    public List<PkIndexEntry> getAdminPagePkIndexes(String projectName, String collName) {
        return pageCache.getAdminPagePkIndexes(projectName, collName);
    }

    public void repositionAfterVacuum(VacuumedPage vacuumed) {
        vacuumed.reposition(Globals.ADMIN_PROJECT_NAME.equals(vacuumed.projectName())
                ? adminCollectionPkIndex(vacuumed.collName())
                : pageCache.pkIndexesForPagesCollection(vacuumed.collName()));
    }

    private Collection<PkIndexEntry> adminCollectionPkIndex(String collName) {
        return switch (collName) {
            case Globals.ADMIN_PROJECTS_COLLECTION_NAME -> projectsPkIndex.values();
            case Globals.ADMIN_COLLECTIONS_COLLECTION_NAME -> collectionsPkIndex.values();
            case Globals.ADMIN_USERS_COLLECTION_NAME -> usersPkIndex.values();
            case Globals.ADMIN_COLLECTION_USAGE_NAME -> collectionUsagePkIndex.values();
            case Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME -> transactionsPkIndex.values();
            case Globals.ADMIN_TRIGGER_RUNS_COLLECTION_NAME -> triggerRunsPkIndex.values();
            case null, default -> pageCache.pkIndexesForPagesCollection(collName);
        };
    }

    public void removeAdminPageEntries(String projectName, String collName) {
        pageCache.removeAdminPageEntries(projectName, collName);
    }

    public long selectPageForInsert(String projectName, String collName, int entryByteSize) {
        return pageCache.selectPageForInsert(projectName, collName, entryByteSize);
    }

    public long[] selectPagesForInsert(String projectName, String collName, int[] entryByteSizes) {
        return pageCache.selectPagesForInsert(projectName, collName, entryByteSizes);
    }

    public PkIndexEntry getPkIndexAdminProjectEntry(String projectName) {
        return projectsPkIndex.get(projectName);
    }

    public void putPkIndexAdminProjectEntry(PkIndexEntry adminPkIndexAdminProjectEntry) {
        projectsPkIndex.put(adminPkIndexAdminProjectEntry.getValue(), adminPkIndexAdminProjectEntry);
    }

    public AdminProjectEntry getAdminProjectEntry(String projectName) {
        return projects.get(projectName);
    }

    public Collection<AdminProjectEntry> getAllAdminProjectEntries() {
        return projects.values();
    }

    public List<String> getUserProjectNames() {
        return projects.keySet().stream().filter(name -> !Globals.ADMIN_PROJECT_NAME.equals(name)).sorted().toList();
    }

    public List<String> getCollectionNamesForProject(String projectName) {
        final var prefix = projectName + Globals.COLL_IDENTIFIER_SEPARATOR;
        return collections.keySet().stream().filter(id -> id.startsWith(prefix))
                .map(id -> id.substring(prefix.length())).sorted().toList();
    }

    public PkIndexEntry getPkIndexAdminCollEntry(String collIdentifier) {
        return collectionsPkIndex.get(collIdentifier);
    }

    public void putPkIndexAdminCollEntry(PkIndexEntry adminPkIndexAdminCollEntry) {
        collectionsPkIndex.put(adminPkIndexAdminCollEntry.getValue(), adminPkIndexAdminCollEntry);
    }

    public AdminCollEntry getAdminCollectionEntry(String projectName, String collName) {
        return collections.get(Cache.getCollectionIdentifier(projectName, collName));
    }

    public void putAdminProjectEntry(AdminProjectEntry dbEntry, PkIndexEntry indexEntry) {
        projects.put(dbEntry.get_id(), dbEntry);
        projectsPkIndex.put(dbEntry.get_id(), indexEntry);
    }

    public void removeAdminProjectEntry(String projectName) {
        projects.remove(projectName);
        projectsPkIndex.remove(projectName);
    }

    public void putAdminCollectionEntry(AdminCollEntry dbEntry, PkIndexEntry indexEntry) {
        final var collIdentifier = dbEntry.get_id();
        collections.put(collIdentifier, dbEntry);
        collectionsPkIndex.put(collIdentifier, indexEntry);
    }

    public void removeAdminCollEntry(String collIdentifier) {
        collections.remove(collIdentifier);
        collectionsPkIndex.remove(collIdentifier);
    }

    public boolean hasIndex(String projectName, String collName, String fieldName) {
        return getIndexesForCollection(projectName, collName).contains(fieldName);
    }

    public Set<String> getIndexesForCollection(String projectName, String collName) {
        final var collection = collections.get(Cache.getCollectionIdentifier(projectName, collName));
        // A collection dropped while a background index event was queued must read as "no indexes"
        // rather than throw, so that event becomes a clean no-op.
        if (collection == null) {
            return Set.of();
        }
        return collection.getIndexes();
    }

    public JsonObject getCollectionSchema(String projectName, String collName) {
        return schemaCache.get(projectName, collName);
    }

    public JsonObject loadSchemaUncached(String projectName, String collName) {
        try {
            final var raw = fs.metadataFiles().readCollectionSchema(projectName, collName);
            if (raw == null || raw.isBlank()) {
                return null;
            }
            final var schema = AdminStamp.unwrappedSchema(eJson.fromJson(raw, JsonObject.class));
            SchemaPatternCaps.warnWhenOver(
                    "Loaded the stored schema of " + Cache.getCollectionIdentifier(projectName, collName), schema);
            return schema;
        } catch (Exception e) {
            logger.warning("Failed to load schema for " + Cache.getCollectionIdentifier(projectName, collName) + ": "
                    + e.getMessage());
            throw new MetadataReadException(
                    "Failed to read the schema for " + Cache.getCollectionIdentifier(projectName, collName), e);
        }
    }

    public void putCollectionSchema(String projectName, String collName, JsonObject schema) {
        schemaCache.put(Cache.getCollectionIdentifier(projectName, collName), schema);
    }

    public void removeCollectionSchema(String projectName, String collName) {
        schemaCache.remove(Cache.getCollectionIdentifier(projectName, collName));
    }

    public void removeCollectionSchemasForProject(String projectName) {
        schemaCache.removeForProject(projectName);
    }

    public ProcedureDefinition getProcedure(String projectName, String name) {
        return procedureCache.get(projectName, name);
    }

    public ProcedureDefinition loadProcedureUncached(String projectName, String name) {
        try {
            final var raw = fs.metadataFiles().readProcedure(projectName, name);
            if (raw == null || raw.isBlank()) {
                return null;
            }
            return ProcedureDefinition.fromJsonObject(eJson.fromJson(raw, JsonObject.class));
        } catch (Exception e) {
            logger.warning("Failed to load procedure " + Cache.getCollectionIdentifier(projectName, name) + ": "
                    + e.getMessage());
            throw new MetadataReadException(
                    "Failed to read the procedure " + Cache.getCollectionIdentifier(projectName, name), e);
        }
    }

    public void putProcedure(String projectName, ProcedureDefinition definition) {
        procedureCache.put(Cache.getCollectionIdentifier(projectName, definition.getName()), definition);
    }

    public void removeProcedure(String projectName, String name) {
        procedureCache.remove(Cache.getCollectionIdentifier(projectName, name));
    }

    public void removeProceduresForProject(String projectName) {
        procedureCache.removeForProject(projectName);
    }

    public ScheduleDefinition getSchedule(String projectName, String name) {
        return scheduleCache.get(projectName, name);
    }

    public ScheduleDefinition loadScheduleUncached(String projectName, String name) {
        try {
            final var raw = fs.metadataFiles().readSchedule(projectName, name);
            if (raw == null || raw.isBlank()) {
                return null;
            }
            return ScheduleDefinition.fromJsonObject(eJson.fromJson(raw, JsonObject.class));
        } catch (Exception e) {
            logger.warning("Failed to load schedule " + Cache.getCollectionIdentifier(projectName, name) + ": "
                    + e.getMessage());
            throw new MetadataReadException(
                    "Failed to read the schedule " + Cache.getCollectionIdentifier(projectName, name), e);
        }
    }

    public void putSchedule(String projectName, ScheduleDefinition definition) {
        scheduleCache.put(Cache.getCollectionIdentifier(projectName, definition.getName()), definition);
    }

    public void removeSchedule(String projectName, String name) {
        scheduleCache.remove(Cache.getCollectionIdentifier(projectName, name));
    }

    public void removeSchedulesForProject(String projectName) {
        scheduleCache.removeForProject(projectName);
    }

    public void removeSchedulesMatching(Predicate<String> keyMatches) {
        scheduleCache.removeIf(keyMatches);
    }

    public EntrypointDefinitions entrypoints() {
        return entrypoints;
    }

    // An empty list doubles as the negative-cache sentinel, so an untriggered collection is read from
    // disk once rather than on every SAVE.
    public List<TriggerDefinition> getTriggersFor(String projectName, String collName) {
        final var id = Cache.getCollectionIdentifier(projectName, collName);
        var cached = triggers.get(id);
        if (cached == null) {
            final var generationAtLoad = triggerGeneration.current();
            cached = loadTriggersUncached(projectName, collName);
            final var loaded = cached;
            triggerGeneration.publishIfCurrent(generationAtLoad, () -> triggers.put(id, loaded));
        }
        return cached;
    }

    public List<TriggerDefinition> loadTriggersUncached(String projectName, String collName) {
        try {
            final var raw = fs.metadataFiles().readTriggers(projectName, collName);
            if (raw == null || raw.isBlank()) {
                return List.of();
            }
            return List.copyOf(TriggerDefinition.fromFileJson(eJson.fromJson(raw, JsonObject.class)));
        } catch (Exception e) {
            logger.warning("Failed to load triggers for " + Cache.getCollectionIdentifier(projectName, collName) + ": "
                    + e.getMessage());
            throw new MetadataReadException(
                    "Failed to read the triggers for " + Cache.getCollectionIdentifier(projectName, collName), e);
        }
    }

    public void putTriggers(String projectName, String collName, List<TriggerDefinition> definitions) {
        triggerGeneration.invalidate(
                () -> triggers.put(Cache.getCollectionIdentifier(projectName, collName), List.copyOf(definitions)));
    }

    public void removeTriggers(String projectName, String collName) {
        triggerGeneration.invalidate(() -> triggers.remove(Cache.getCollectionIdentifier(projectName, collName)));
    }

    public void removeTriggersForProject(String projectName) {
        final var prefix = projectName + Globals.COLL_IDENTIFIER_SEPARATOR;
        triggerGeneration.invalidate(() -> triggers.removeIf(id -> id.startsWith(prefix)));
    }

    // Must leave no entry behind: a cached empty list would make an already-queued trigger silently
    // not fire when TriggerDispatcher looks the list up again.
    public void removeTriggersMatching(Predicate<String> keyMatches) {
        triggerGeneration.invalidate(() -> triggers.removeIf(keyMatches));
    }

    public MetadataCacheStats metadataCacheStats() {
        return new MetadataCacheStats(procedures.bytes(), procedures.size(), triggers.bytes(), triggers.size(),
                collectionSchemas.bytes(), collectionSchemas.size(), schedules.bytes(), schedules.size(),
                entrypoints.bytes(), entrypoints.size(), metadataMisses.size());
    }

    public AdminUserEntry getAdminUserEntry(String username) {
        return users.get(username);
    }

    public Collection<AdminUserEntry> getAllAdminUserEntries() {
        return users.values();
    }

    public void putAdminUserEntry(AdminUserEntry userEntry, PkIndexEntry indexEntry) {
        users.put(userEntry.get_id(), userEntry);
        usersPkIndex.put(userEntry.get_id(), indexEntry);
    }

    public void removeAdminUserEntry(String username) {
        users.remove(username);
        usersPkIndex.remove(username);
    }

    public PkIndexEntry getPkIndexAdminUserEntry(String username) {
        return usersPkIndex.get(username);
    }

    public PkIndexEntry getPkIndexCollectionUsage(String usageId) {
        return collectionUsagePkIndex.get(usageId);
    }

    public void putPkIndexCollectionUsage(PkIndexEntry indexEntry) {
        collectionUsagePkIndex.put(indexEntry.getValue(), indexEntry);
    }

    public void removePkIndexCollectionUsage(String usageId) {
        collectionUsagePkIndex.remove(usageId);
    }

    public Map<String, PkIndexEntry> getCollectionUsagePkIndexes() {
        return collectionUsagePkIndex;
    }

    public PkIndexEntry getPkIndexTransaction(String opId) {
        return transactionsPkIndex.get(opId);
    }

    public void putPkIndexTransaction(PkIndexEntry indexEntry) {
        transactionsPkIndex.put(indexEntry.getValue(), indexEntry);
    }

    public void removePkIndexTransaction(String opId) {
        transactionsPkIndex.remove(opId);
    }

    public Map<String, PkIndexEntry> getTransactionPkIndexes() {
        return transactionsPkIndex;
    }

    public PkIndexEntry getPkIndexTriggerRun(String recordId) {
        return triggerRunsPkIndex.get(recordId);
    }

    public void putPkIndexTriggerRun(PkIndexEntry indexEntry) {
        triggerRunsPkIndex.put(indexEntry.getValue(), indexEntry);
    }

    public void removePkIndexTriggerRun(String recordId) {
        triggerRunsPkIndex.remove(recordId);
    }

    public NavigableMap<String, PkIndexEntry> getTriggerRunPkIndexes() {
        return triggerRunsPkIndex;
    }
}
