package org.ccg.mpp.cache;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.function.Predicate;
import org.ccg.mpp.data.EntrypointDefinition;
import org.ccg.mpp.data.PkIndexEntry;
import org.ccg.mpp.data.ProcedureDefinition;
import org.ccg.mpp.data.ScheduleDefinition;
import org.ccg.mpp.data.TriggerDefinition;
import org.ccg.mpp.data.admin.AdminCollEntry;
import org.ccg.mpp.data.admin.AdminPageEntry;
import org.ccg.mpp.data.admin.AdminProjectEntry;
import org.ccg.mpp.data.admin.AdminUserEntry;
import org.ccg.mpp.ejson.elements.JsonObject;

interface AdminCacheDelegate {
    AdminCache adminCache();

    default void loadAdminData() throws IOException {
        adminCache().loadAdminData();
    }

    default long selectPageForInsert(String projectName, String collName, int entryByteSize) {
        return adminCache().selectPageForInsert(projectName, collName, entryByteSize);
    }

    default long[] selectPagesForInsert(String projectName, String collName, int[] entryByteSizes) {
        return adminCache().selectPagesForInsert(projectName, collName, entryByteSizes);
    }

    default PkIndexEntry getPkIndexAdminProjectEntry(String projectName) {
        return adminCache().getPkIndexAdminProjectEntry(projectName);
    }

    default AdminProjectEntry getAdminProjectEntry(String projectName) {
        return adminCache().getAdminProjectEntry(projectName);
    }

    default Collection<AdminProjectEntry> getAllAdminProjectEntries() {
        return adminCache().getAllAdminProjectEntries();
    }

    default List<String> getUserProjectNames() {
        return adminCache().getUserProjectNames();
    }

    default List<String> getCollectionNamesForProject(String projectName) {
        return adminCache().getCollectionNamesForProject(projectName);
    }

    default PkIndexEntry getPkIndexAdminCollEntry(String collIdentifier) {
        return adminCache().getPkIndexAdminCollEntry(collIdentifier);
    }

    default AdminCollEntry getAdminCollectionEntry(String projectName, String collName) {
        return adminCache().getAdminCollectionEntry(projectName, collName);
    }

    default List<AdminPageEntry> getAdminPageEntries(String projectName, String collName) {
        return adminCache().getAdminPageEntries(projectName, collName);
    }

    default AdminPageEntry getAdminPageEntry(String projectName, String collName, long page) {
        return adminCache().getAdminPageEntry(projectName, collName, page);
    }

    default void addAdminPageEntries(String projectName, String collName, AdminPageEntry adminPageEntry) {
        adminCache().addAdminPageEntries(projectName, collName, adminPageEntry);
    }

    default void addAdminPageEntries(String projectName, String collName, List<AdminPageEntry> adminPageEntries) {
        adminCache().addAdminPageEntries(projectName, collName, adminPageEntries);
    }

    default void updatePageSizeInMemory(String projectName, String collName, long page, long bytesDelta) {
        adminCache().updatePageSizeInMemory(projectName, collName, page, bytesDelta);
    }

    default void updatePageSizeInMemory(String projectName, String collName, long page, long bytesDelta,
            int addedEntries) {
        adminCache().updatePageSizeInMemory(projectName, collName, page, bytesDelta, addedEntries);
    }

    default void updatePageSizeForUpdateInMemory(String projectName, String collName, long page, long bytesDelta) {
        adminCache().updatePageSizeForUpdateInMemory(projectName, collName, page, bytesDelta);
    }

    default List<PkIndexEntry> getAdminPagePkIndexes(String projectName, String collName) {
        return adminCache().getAdminPagePkIndexes(projectName, collName);
    }

    default void removeAdminPageEntries(String projectName, String collName) {
        adminCache().removeAdminPageEntries(projectName, collName);
    }

    default void putAdminProjectEntry(AdminProjectEntry dbEntry, PkIndexEntry indexEntry) {
        adminCache().putAdminProjectEntry(dbEntry, indexEntry);
    }

    default void removeAdminProjectEntry(String projectName) {
        adminCache().removeAdminProjectEntry(projectName);
    }

    default void putAdminCollectionEntry(AdminCollEntry dbEntry, PkIndexEntry indexEntry) {
        adminCache().putAdminCollectionEntry(dbEntry, indexEntry);
    }

    default void removeAdminCollEntry(String collIdentifier) {
        adminCache().removeAdminCollEntry(collIdentifier);
    }

    default Set<String> getIndexesForCollection(String projectName, String collName) {
        return adminCache().getIndexesForCollection(projectName, collName);
    }

    default JsonObject getCollectionSchema(String projectName, String collName) {
        return adminCache().getCollectionSchema(projectName, collName);
    }

    default void putCollectionSchema(String projectName, String collName, JsonObject schema) {
        adminCache().putCollectionSchema(projectName, collName, schema);
    }

    default void removeCollectionSchema(String projectName, String collName) {
        adminCache().removeCollectionSchema(projectName, collName);
    }

    default void removeCollectionSchemasForProject(String projectName) {
        adminCache().removeCollectionSchemasForProject(projectName);
    }

    default JsonObject loadSchemaUncached(String projectName, String collName) {
        return adminCache().loadSchemaUncached(projectName, collName);
    }

    default ProcedureDefinition getProcedure(String projectName, String name) {
        return adminCache().getProcedure(projectName, name);
    }

    default void putProcedure(String projectName, ProcedureDefinition definition) {
        adminCache().putProcedure(projectName, definition);
    }

    default void removeProcedure(String projectName, String name) {
        adminCache().removeProcedure(projectName, name);
    }

    default void removeProceduresForProject(String projectName) {
        adminCache().removeProceduresForProject(projectName);
    }

    default List<TriggerDefinition> getTriggersFor(String projectName, String collName) {
        return adminCache().getTriggersFor(projectName, collName);
    }

    default void putTriggers(String projectName, String collName, List<TriggerDefinition> definitions) {
        adminCache().putTriggers(projectName, collName, definitions);
    }

    default void removeTriggers(String projectName, String collName) {
        adminCache().removeTriggers(projectName, collName);
    }

    default void removeTriggersMatching(Predicate<String> keyMatches) {
        adminCache().removeTriggersMatching(keyMatches);
    }

    default ScheduleDefinition getSchedule(String projectName, String name) {
        return adminCache().getSchedule(projectName, name);
    }

    default void putSchedule(String projectName, ScheduleDefinition definition) {
        adminCache().putSchedule(projectName, definition);
    }

    default void removeSchedule(String projectName, String name) {
        adminCache().removeSchedule(projectName, name);
    }

    default void removeSchedulesForProject(String projectName) {
        adminCache().removeSchedulesForProject(projectName);
    }

    default void removeSchedulesMatching(Predicate<String> keyMatches) {
        adminCache().removeSchedulesMatching(keyMatches);
    }

    default EntrypointDefinition getEntrypoint(String projectName, String name) {
        return adminCache().entrypoints().get(projectName, name);
    }

    default void putEntrypoint(String projectName, EntrypointDefinition definition) {
        adminCache().entrypoints().put(projectName, definition);
    }

    default void removeEntrypoint(String projectName, String name) {
        adminCache().entrypoints().remove(projectName, name);
    }

    default void removeEntrypointsForProject(String projectName) {
        adminCache().entrypoints().removeForProject(projectName);
    }

    default MetadataCacheStats metadataCacheStats() {
        return adminCache().metadataCacheStats();
    }

    default AdminUserEntry getAdminUserEntry(String username) {
        return adminCache().getAdminUserEntry(username);
    }

    default Collection<AdminUserEntry> getAllAdminUserEntries() {
        return adminCache().getAllAdminUserEntries();
    }

    default void putAdminUserEntry(AdminUserEntry userEntry, PkIndexEntry indexEntry) {
        adminCache().putAdminUserEntry(userEntry, indexEntry);
    }

    default void removeAdminUserEntry(String username) {
        adminCache().removeAdminUserEntry(username);
    }

    default PkIndexEntry getPkIndexAdminUserEntry(String username) {
        return adminCache().getPkIndexAdminUserEntry(username);
    }

    default PkIndexEntry getPkIndexCollectionUsage(String usageId) {
        return adminCache().getPkIndexCollectionUsage(usageId);
    }

    default void putPkIndexCollectionUsage(PkIndexEntry indexEntry) {
        adminCache().putPkIndexCollectionUsage(indexEntry);
    }

    default void removePkIndexCollectionUsage(String usageId) {
        adminCache().removePkIndexCollectionUsage(usageId);
    }

    default Map<String, PkIndexEntry> getCollectionUsagePkIndexes() {
        return adminCache().getCollectionUsagePkIndexes();
    }

    default PkIndexEntry getPkIndexTransaction(String opId) {
        return adminCache().getPkIndexTransaction(opId);
    }

    default void putPkIndexTransaction(PkIndexEntry indexEntry) {
        adminCache().putPkIndexTransaction(indexEntry);
    }

    default void removePkIndexTransaction(String opId) {
        adminCache().removePkIndexTransaction(opId);
    }

    default Map<String, PkIndexEntry> getTransactionPkIndexes() {
        return adminCache().getTransactionPkIndexes();
    }

    default PkIndexEntry getPkIndexTriggerRun(String recordId) {
        return adminCache().getPkIndexTriggerRun(recordId);
    }

    default void putPkIndexTriggerRun(PkIndexEntry indexEntry) {
        adminCache().putPkIndexTriggerRun(indexEntry);
    }

    default void removePkIndexTriggerRun(String recordId) {
        adminCache().removePkIndexTriggerRun(recordId);
    }

    default NavigableMap<String, PkIndexEntry> getTriggerRunPkIndexes() {
        return adminCache().getTriggerRunPkIndexes();
    }
}
