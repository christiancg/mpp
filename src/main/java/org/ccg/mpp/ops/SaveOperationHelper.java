package org.ccg.mpp.ops;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.ccg.mpp.bckg_ops.BackgroundTaskManager;
import org.ccg.mpp.bckg_ops.PendingIndexWrites;
import org.ccg.mpp.bckg_ops.events.BulkEntityEvent;
import org.ccg.mpp.bckg_ops.events.EntityEvent;
import org.ccg.mpp.bckg_ops.events.EntityRef;
import org.ccg.mpp.bckg_ops.events.Event;
import org.ccg.mpp.bckg_ops.events.EventType;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.cache.WriterPkRows;
import org.ccg.mpp.cluster.HybridClock;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.DbEntry;
import org.ccg.mpp.data.IndexedDbEntry;
import org.ccg.mpp.data.PkIndexEntry;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.ex.PartialBulkSaveException;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.fs.PagePlacement;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.listen.DirtySink;
import org.ccg.mpp.listen.ListenManager;
import org.ccg.mpp.ops.admin.CollectionIncarnation;
import org.ccg.mpp.ops.index.IndexedValue;
import org.ccg.mpp.ops.req.BulkSaveRequest;
import org.ccg.mpp.ops.req.SaveRequest;
import org.ccg.mpp.ops.resp.BulkSaveResponse;
import org.ccg.mpp.ops.resp.OperationResponse;
import org.ccg.mpp.ops.resp.SaveResponse;

public final class SaveOperationHelper {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    // Not final so tests can substitute a task manager whose workers are never started.
    @SuppressWarnings("FieldMayBeFinal")
    private static BackgroundTaskManager taskManager = IocContainer.get(BackgroundTaskManager.class);
    private static final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);
    private static final ListenManager listenManager = IocContainer.get(ListenManager.class);
    private static final Configuration configuration = Configuration.getInstance();
    private static final HybridClock hybridClock = IocContainer.get(HybridClock.class);

    private SaveOperationHelper() {
    }

    private static OperationResponse reconcileSaveId(SaveRequest saveRequest) {
        final var object = saveRequest.getObject();
        final var requestId = saveRequest.get_id();
        final var objectId = object.get(Globals.PK_FIELD) instanceof JsonString jsonString
                ? jsonString.getValue()
                : null;
        if (requestId != null && objectId != null && !requestId.equals(objectId)) {
            return new OperationResponse(OperationType.SAVE,
                    "The _id sent with the request does not match the _id inside the object",
                    ErrorCode.VALIDATION_ERROR);
        }
        if (requestId != null && objectId == null) {
            object.addProperty(Globals.PK_FIELD, requestId);
        } else if (requestId == null && objectId != null) {
            saveRequest.set_id(objectId);
        }
        return null;
    }

    // The caller must already hold the collection write lock.
    public static OperationResponse executeSave(SaveRequest saveRequest) throws Exception {
        return executeSave(saveRequest, 0L, listenManager);
    }

    public static OperationResponse executeSave(SaveRequest saveRequest, long version, DirtySink sink)
            throws Exception {
        final var names = Cache.canonicalNames(saveRequest.getProjectName(), saveRequest.getCollectionName());
        final var projectName = names.projectName();
        final var collName = names.collName();
        final var idError = reconcileSaveId(saveRequest);
        if (idError != null) {
            return idError;
        }
        final var entry = DbEntry.fromJsonObject(projectName, collName, saveRequest.getObject());
        entry.setVersion(stampFor(version));
        final var encodedLine = entry.encodeLine();
        final var sizeError = EntrySizeGuard.check(entry, OperationType.SAVE);
        if (sizeError != null) {
            return sizeError;
        }
        final var requestId = saveRequest.get_id();
        final var pkRows = cache.pkRowsForWriter(projectName, collName,
                requestId == null ? List.of() : List.of(requestId));
        final var idxEntry = requestId == null ? null : pkRows.row(requestId);
        final var priorFields = PriorIndexedValues.fieldsOf(projectName, collName);
        var eventType = EventType.CREATED;
        PkIndexEntry savedPkIndexEntry;
        final long pendingGeneration;
        final Map<String, IndexedValue> priors;
        if (idxEntry != null) {
            final var placement = placementFor(projectName, collName, idxEntry, encodedLine.length);
            if (placement == PagePlacement.RELOCATE) {
                // The grown document no longer fits its page; relocate as delete + insert so page metadata
                // and field indexes stay correct through the standard background events.
                return relocateWithListensHeld(projectName, collName, entry, idxEntry, pkRows, encodedLine,
                        saveRequest.isBackgroundFollowOn(), sink);
            }
            priors = PriorIndexedValues.ofStored(priorFields, projectName, collName, idxEntry);
            adoptResidentId(entry, idxEntry);
            entry.setPage(idxEntry.getPage());
            final var previousLength = idxEntry.getLength();
            pendingGeneration = pendingIndexWrites.mark(projectName, collName, entry.get_id());
            savedPkIndexEntry = pendingIndexWrites.writeWhilePending(projectName, collName, List.of(entry.get_id()),
                    pendingGeneration, () -> fs.updateFromCollection(entry, idxEntry, encodedLine, placement,
                            pkRows.vacuumRows(), cache.pageOccupancy(projectName, collName)));
            cache.updatePageSizeForUpdateInMemory(projectName, collName, savedPkIndexEntry.getPage(),
                    savedPkIndexEntry.getLength() - previousLength);
            eventType = EventType.UPDATED;
        } else {
            priors = PriorIndexedValues.of(priorFields, null);
            entry.setPage(cache.selectPageForInsert(projectName, collName, entry.byteSize()));
            pendingGeneration = pendingIndexWrites.mark(projectName, collName, entry.get_id());
            savedPkIndexEntry = pendingIndexWrites.writeWhilePending(projectName, collName, List.of(entry.get_id()),
                    pendingGeneration, () -> fs.insertIntoCollection(entry, encodedLine));
            cache.updatePageSizeInMemory(projectName, collName, savedPkIndexEntry.getPage(),
                    savedPkIndexEntry.getLength());
        }
        pkRows.store(savedPkIndexEntry, eventType == EventType.UPDATED);
        cache.addEntryToCache(projectName, collName, entry);
        submit(new EntityEvent(eventType, projectName, collName, EntityRef.of(entry, priors),
                CollectionIncarnation.current(projectName, collName), pendingGeneration),
                saveRequest.isBackgroundFollowOn());
        sink.markDirty(projectName, collName);
        cache.signalIfOverBudget();
        CollectionAccessHelper.recordCollectionAccess(projectName, collName);
        return new SaveResponse("Successfully saved", savedPkIndexEntry.getValue(), eventType == EventType.CREATED);
    }

    private static void adoptResidentId(DbEntry entry, PkIndexEntry residentRow) {
        final var residentId = residentRow.getValue();
        entry.adoptId(residentId);
        entry.getData().addProperty(Globals.PK_FIELD, residentId);
    }

    private static Set<String> idSetOf(List<IndexedDbEntry> entries) {
        final var ids = new HashSet<String>(entries.size() * 2);
        for (final var entry : entries) {
            ids.add(entry.get_id());
        }
        return ids;
    }

    private static long stampFor(long version) {
        if (version <= 0) {
            return hybridClock.next();
        }
        hybridClock.observe(version);
        return version;
    }

    // The caller must already hold the collection write lock.
    public static OperationResponse executeBulkSave(BulkSaveRequest bulkSaveRequest) throws Exception {
        return executeBulkSave(bulkSaveRequest, null, listenManager);
    }

    // A non-null versions list supplies the owner's version per object, so replicas do not assign their own.
    public static OperationResponse executeBulkSave(BulkSaveRequest bulkSaveRequest, List<Long> versions,
            DirtySink sink) throws Exception {
        return executeBulkSave(bulkSaveRequest, versions, sink, true);
    }

    public static OperationResponse executeBulkSave(BulkSaveRequest bulkSaveRequest, List<Long> versions,
            DirtySink sink, boolean populateCache) throws Exception {
        final var names = Cache.canonicalNames(bulkSaveRequest.getProjectName(), bulkSaveRequest.getCollectionName());
        final var projectName = names.projectName();
        final var collName = names.collName();
        final var entries = new ArrayList<DbEntry>();
        final var objects = bulkSaveRequest.getObjects();
        for (var i = 0; i < objects.size(); i++) {
            final var entry = DbEntry.fromJsonObject(projectName, collName, objects.get(i));
            final var version = versions != null ? versions.get(i) : null;
            entry.setVersion(stampFor(version != null ? version : 0L));
            entries.add(entry);
        }
        final var linesById = new HashMap<String, byte[]>();
        for (var entry : entries) {
            final var encodedLine = entry.encodeLine();
            final var sizeError = EntrySizeGuard.check(entry, OperationType.BULK_SAVE);
            if (sizeError != null) {
                return sizeError;
            }
            linesById.put(entry.get_id(), encodedLine);
        }
        final var seenIds = new HashSet<String>();
        for (var entry : entries) {
            if (!seenIds.add(entry.get_id())) {
                return new OperationResponse(OperationType.BULK_SAVE,
                        "Duplicate _id in bulk save request: " + entry.get_id(), ErrorCode.DUPLICATE_ID);
            }
        }
        seenIds.remove(null);
        final var pkRows = cache.pkRowsForWriter(projectName, collName, seenIds);
        final var primaryKeyIndex = pkRows.editable();
        final var pendingGeneration = pendingIndexWrites.mark(projectName, collName, idsOf(entries));
        final var indexedDbEntriesToUpdate = new ArrayList<IndexedDbEntry>();
        for (var i : entries) {
            final var data = i.getData();
            if (data.has(org.ccg.mpp.config.Globals.PK_FIELD)) {
                final var id = data.get(org.ccg.mpp.config.Globals.PK_FIELD).asJsonString().getValue();
                i.set_id(id);
                final var foundIndex = pkRows.row(id);
                if (foundIndex != null) {
                    adoptResidentId(i, foundIndex);
                    final var indexedDbEntry = new IndexedDbEntry();
                    indexedDbEntry.setIndex(foundIndex);
                    indexedDbEntry.setProjectName(projectName);
                    indexedDbEntry.setCollectionName(collName);
                    indexedDbEntry.set_id(foundIndex.getValue());
                    indexedDbEntry.setData(data);
                    indexedDbEntry.setVersion(i.getVersion());
                    indexedDbEntriesToUpdate.add(indexedDbEntry);
                }
            }
        }
        final var idsToUpdate = idSetOf(indexedDbEntriesToUpdate);
        final var entriesToInsert = entries.stream().filter(dbEntry -> !idsToUpdate.contains(dbEntry.get_id()))
                .toList();
        final var priors = PriorIndexedValues.ofRows(PriorIndexedValues.fieldsOf(projectName, collName),
                rowsOf(indexedDbEntriesToUpdate), idsOf(entriesToInsert));
        final List<IndexedDbEntry> updatedIndexEntries = new ArrayList<>();
        if (!indexedDbEntriesToUpdate.isEmpty()) {
            final List<IndexedDbEntry> bulkUpdated;
            try {
                bulkUpdated = fs.bulkUpdateFromCollection(projectName, collName, indexedDbEntriesToUpdate,
                        pkRows.vacuumRows(), updated -> linesById.get(updated.get_id()),
                        cache.pageOccupancy(projectName, collName));
            } catch (Exception e) {
                clearUncommitted(projectName, collName, entries, List.of(), pendingGeneration);
                cache.userCache().evictPkIndex(projectName, collName);
                throw e;
            }
            updatedIndexEntries.addAll(bulkUpdated);
            applyUpdateSizeDeltas(projectName, collName, bulkUpdated);
            if (primaryKeyIndex != null) {
                PkIndexEdits.replaceAll(primaryKeyIndex, rowsOf(bulkUpdated));
            }
        }
        final var insertedIndexEntries = new ArrayList<IndexedDbEntry>();
        try {
            if (!entriesToInsert.isEmpty()) {
                final var sizes = new int[entriesToInsert.size()];
                for (var index = 0; index < sizes.length; index++) {
                    sizes[index] = linesById.get(entriesToInsert.get(index).get_id()).length;
                }
                final var targets = cache.selectPagesForInsert(projectName, collName, sizes);
                for (var index = 0; index < sizes.length; index++) {
                    entriesToInsert.get(index).setPage(targets[index]);
                }
                insertedIndexEntries.addAll(fs.bulkInsertIntoCollection(projectName, collName, entriesToInsert,
                        DbEntry::getPage, inserted -> linesById.get(inserted.get_id())));
                addInsertedPageSizes(projectName, collName, insertedIndexEntries);
            }
            if (primaryKeyIndex != null) {
                PkIndexEdits.insertAll(primaryKeyIndex, rowsOf(insertedIndexEntries));
            }
        } catch (Exception e) {
            final var committedUpdates = toDbEntries(updatedIndexEntries);
            final var committedInserts = toDbEntries(insertedIndexEntries);
            publishCommittedWrites(projectName, collName,
                    new CommittedWrites(committedUpdates, committedInserts, priors), pendingGeneration,
                    bulkSaveRequest.isBackgroundFollowOn(), sink, populateCache);
            final var committed = new ArrayList<>(committedUpdates);
            committed.addAll(committedInserts);
            clearUncommitted(projectName, collName, entries, committed, pendingGeneration);
            throw withCommittedPart(e, committedInserts, committedUpdates);
        }
        final var updatedDbEntries = toDbEntries(updatedIndexEntries);
        final var insertedDbEntries = toDbEntries(insertedIndexEntries);
        publishCommittedWrites(projectName, collName, new CommittedWrites(updatedDbEntries, insertedDbEntries, priors),
                pendingGeneration, bulkSaveRequest.isBackgroundFollowOn(), sink, populateCache);
        cache.signalIfOverBudget();
        if (populateCache) {
            CollectionAccessHelper.recordCollectionAccess(projectName, collName);
        }
        return new BulkSaveResponse("Successfully saved entries", idsOf(insertedDbEntries), idsOf(updatedDbEntries));
    }

    private static List<PkIndexEntry> rowsOf(List<IndexedDbEntry> entries) {
        if (entries.isEmpty()) {
            return List.of();
        }
        return entries.stream().map(IndexedDbEntry::getIndex).toList();
    }

    private static void addInsertedPageSizes(String projectName, String collName, List<IndexedDbEntry> inserted) {
        final var totalsByPage = new HashMap<Long, long[]>();
        for (final var insertedEntry : inserted) {
            final var index = insertedEntry.getIndex();
            final var totals = totalsByPage.computeIfAbsent(index.getPage(), _ -> new long[2]);
            totals[0] += index.getLength();
            totals[1]++;
        }
        totalsByPage.forEach((page, totals) -> cache.updatePageSizeInMemory(projectName, collName, page, totals[0],
                (int) totals[1]));
    }

    private static void applyUpdateSizeDeltas(String projectName, String collName, List<IndexedDbEntry> updated) {
        for (final var updatedEntry : updated) {
            final var index = updatedEntry.getIndex();
            cache.updatePageSizeForUpdateInMemory(projectName, collName, index.getPage(),
                    index.getLength() - updatedEntry.getPreviousByteSize());
        }
    }

    private record CommittedWrites(List<DbEntry> updated, List<DbEntry> inserted,
            Map<String, Map<String, IndexedValue>> priors) {
    }

    private static void publishCommittedWrites(String projectName, String collName, CommittedWrites writes,
            long pendingGeneration, boolean followOn, DirtySink sink, boolean populateCache) {
        if (populateCache) {
            cache.addEntriesToCache(projectName, collName, writes.updated());
            cache.addEntriesToCache(projectName, collName, writes.inserted());
        } else {
            for (final var entry : writes.updated()) {
                cache.evictEntry(projectName, collName, entry.get_id());
            }
        }
        submit(new BulkEntityEvent(projectName, collName, writes.inserted(), writes.updated(),
                CollectionIncarnation.current(projectName, collName), pendingGeneration, writes.priors()), followOn);
        sink.markDirty(projectName, collName);
    }

    private static void clearUncommitted(String projectName, String collName, List<DbEntry> entries,
            List<DbEntry> committed, long pendingGeneration) {
        final var committedIds = new HashSet<>(idsOf(committed));
        final var uncommitted = idsOf(entries).stream().filter(id -> !committedIds.contains(id)).toList();
        pendingIndexWrites.clear(projectName, collName, uncommitted, pendingGeneration);
    }

    private static Exception withCommittedPart(Exception failure, List<DbEntry> inserted, List<DbEntry> updated) {
        if (inserted.isEmpty() && updated.isEmpty()) {
            return failure;
        }
        return new PartialBulkSaveException(
                new BulkSaveResponse("Partially saved entries", idsOf(inserted), idsOf(updated)), failure);
    }

    private static List<DbEntry> toDbEntries(List<IndexedDbEntry> entries) {
        return entries.stream().map(IndexedDbEntry::toDbEntry).toList();
    }

    private static List<String> idsOf(List<DbEntry> entries) {
        return entries.stream().map(DbEntry::get_id).toList();
    }

    private static SaveResponse relocateWithListensHeld(String projectName, String collName, DbEntry entry,
            PkIndexEntry idxEntry, WriterPkRows pkRows, byte[] encodedLine, boolean followOn, DirtySink sink)
            throws Exception {
        try (var batch = listenManager.openBatch(sink, List.of(Cache.getCollectionIdentifier(projectName, collName)))) {
            final var relocatedPkIndexEntry = relocateOnGrowUpdate(projectName, collName, entry, idxEntry, pkRows,
                    encodedLine, followOn);
            batch.markDirty(projectName, collName);
            CollectionAccessHelper.recordCollectionAccess(projectName, collName);
            return new SaveResponse("Successfully saved", relocatedPkIndexEntry.getValue());
        }
    }

    public static PagePlacement placementFor(String projectName, String collName, PkIndexEntry idxEntry,
            int lineLength) {
        final var pageEntry = cache.getAdminPageEntry(projectName, collName, idxEntry.getPage());
        if (pageEntry == null) {
            return null;
        }
        return PagePlacement.forUpdate(pageEntry.getPageSize(),
                fs.pageFileLength(projectName, collName, idxEntry.getPage()), lineLength,
                configuration.getMaxPageSize());
    }

    // The caller must already hold the collection write lock.
    public static PkIndexEntry relocateOnGrowUpdate(String projectName, String collName, DbEntry entry,
            PkIndexEntry idxEntry, List<PkIndexEntry> primaryKeyIndex) throws Exception {
        return relocateOnGrowUpdate(projectName, collName, entry, idxEntry, WriterPkRows.of(primaryKeyIndex), null,
                false);
    }

    private static PkIndexEntry relocateOnGrowUpdate(String projectName, String collName, DbEntry entry,
            PkIndexEntry idxEntry, WriterPkRows pkRows, byte[] encodedLine, boolean followOn) throws Exception {
        final var oldEntry = detachedCopy(cache.getById(projectName, collName, idxEntry));
        final var priorFields = PriorIndexedValues.fieldsOf(projectName, collName);
        oldEntry.setPage(idxEntry.getPage());
        oldEntry.setVersion(idxEntry.getVersion());
        final var line = encodedLine != null ? encodedLine : entry.encodeLine();
        final var targetPage = cache.selectPageForInsert(projectName, collName, line.length);
        final var deleteGeneration = pendingIndexWrites.mark(projectName, collName, entry.get_id());
        final var createGeneration = pendingIndexWrites.mark(projectName, collName, entry.get_id());
        final PkIndexEntry relocatedPkIndexEntry;
        try {
            relocatedPkIndexEntry = pendingIndexWrites.writeWhilePending(projectName, collName, List.of(entry.get_id()),
                    deleteGeneration, () -> fs.relocate(entry, idxEntry, targetPage, line, pkRows.vacuumRows(),
                            cache.pageOccupancy(projectName, collName)));
        } catch (Exception e) {
            pendingIndexWrites.clear(projectName, collName, entry.get_id(), createGeneration);
            throw e;
        }
        cache.updatePageSizeInMemory(projectName, collName, idxEntry.getPage(), -idxEntry.getLength(), -1);
        cache.evictEntry(projectName, collName, entry.get_id());
        submit(new EntityEvent(EventType.DELETED, projectName, collName,
                EntityRef.of(oldEntry, PriorIndexedValues.of(priorFields, oldEntry)),
                CollectionIncarnation.current(projectName, collName), deleteGeneration), followOn);
        cache.updatePageSizeInMemory(projectName, collName, relocatedPkIndexEntry.getPage(),
                relocatedPkIndexEntry.getLength());
        pkRows.store(relocatedPkIndexEntry, true);
        cache.addEntryToCache(projectName, collName, entry);
        submit(new EntityEvent(EventType.CREATED, projectName, collName,
                EntityRef.of(entry, PriorIndexedValues.of(priorFields, null)),
                CollectionIncarnation.current(projectName, collName), createGeneration), followOn);
        return relocatedPkIndexEntry;
    }

    private static void submit(Event event, boolean followOn) {
        if (followOn) {
            taskManager.submitFollowOn(event);
        } else {
            taskManager.submitBackgroundTask(event);
        }
    }

    private static DbEntry detachedCopy(DbEntry entry) {
        final var copy = DbEntry.fromJsonObject(entry.getProjectName(), entry.getCollectionName(), entry.getData());
        copy.set_id(entry.get_id());
        copy.setPage(entry.getPage());
        copy.setVersion(entry.getVersion());
        copy.setPreviousByteSize(entry.getPreviousByteSize());
        return copy;
    }
}
