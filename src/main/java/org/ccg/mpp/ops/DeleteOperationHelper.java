package org.ccg.mpp.ops;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.TreeMap;
import org.ccg.mpp.bckg_ops.BackgroundTaskManager;
import org.ccg.mpp.bckg_ops.PendingIndexWrites;
import org.ccg.mpp.bckg_ops.events.EntityEvent;
import org.ccg.mpp.bckg_ops.events.EntityRef;
import org.ccg.mpp.bckg_ops.events.EventType;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.cache.WriterPkRows;
import org.ccg.mpp.data.DbEntry;
import org.ccg.mpp.data.PkIndexEntry;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.listen.DirtySink;
import org.ccg.mpp.listen.ListenManager;
import org.ccg.mpp.ops.admin.CollectionIncarnation;
import org.ccg.mpp.ops.req.DeleteRequest;
import org.ccg.mpp.ops.resp.DeleteResponse;
import org.ccg.mpp.ops.resp.OperationResponse;

public final class DeleteOperationHelper {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    @SuppressWarnings("FieldMayBeFinal")
    private static BackgroundTaskManager taskManager = IocContainer.get(BackgroundTaskManager.class);
    private static final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);
    private static final ListenManager listenManager = IocContainer.get(ListenManager.class);

    private DeleteOperationHelper() {
    }

    // The caller must already hold the collection write lock.
    public static OperationResponse executeDelete(DeleteRequest deleteRequest) throws Exception {
        return executeDelete(deleteRequest, listenManager);
    }

    public static OperationResponse executeDelete(DeleteRequest deleteRequest, DirtySink sink) throws Exception {
        final var projectName = deleteRequest.getProjectName();
        final var collName = deleteRequest.getCollectionName();
        final var id = deleteRequest.get_id();
        final var pkRows = cache.pkRowsForWriter(projectName, collName, List.of(id));
        final var idxEntry = pkRows.row(id);
        if (idxEntry == null) {
            return new OperationResponse(OperationType.DELETE, "Entry with id " + id + " not found",
                    ErrorCode.ENTRY_NOT_FOUND);
        }
        final var entryToBeDeleted = onPage(cache.getById(projectName, collName, idxEntry), idxEntry.getPage());
        final var deletedRef = EntityRef.of(entryToBeDeleted,
                PriorIndexedValues.of(PriorIndexedValues.fieldsOf(projectName, collName), entryToBeDeleted));
        final var pendingGeneration = pendingIndexWrites.mark(projectName, collName, entryToBeDeleted.get_id());
        pendingIndexWrites.writeWhilePending(projectName, collName, List.of(entryToBeDeleted.get_id()),
                pendingGeneration, () -> {
                    fs.deleteFromCollection(idxEntry, pkRows.vacuumRows(), cache.pageOccupancy(projectName, collName));
                    return null;
                });
        cache.updatePageSizeInMemory(projectName, collName, idxEntry.getPage(), -idxEntry.getLength(), -1);
        pkRows.remove(id);
        cache.evictEntry(projectName, collName, entryToBeDeleted.get_id());
        taskManager.submitBackgroundTask(new EntityEvent(EventType.DELETED, projectName, collName, deletedRef,
                CollectionIncarnation.current(projectName, collName), pendingGeneration));
        sink.markDirty(projectName, collName);
        CollectionAccessHelper.recordCollectionAccess(projectName, collName);
        return new DeleteResponse("Entry with id " + id + " deleted successfully");
    }

    public static List<String> executeBulkDelete(String projectName, String collName, List<String> ids, DirtySink sink,
            boolean populateCache) throws Exception {
        final var distinctIds = new LinkedHashSet<>(ids);
        final var pkRows = cache.pkRowsForWriter(projectName, collName, distinctIds);
        final var rows = rowsOf(pkRows, distinctIds);
        if (rows.isEmpty()) {
            return List.of();
        }
        final var deletedIds = new ArrayList<String>(rows.size());
        for (final var row : rows) {
            deletedIds.add(row.getValue());
        }
        final var priors = PriorIndexedValues.ofRows(PriorIndexedValues.fieldsOf(projectName, collName), rows,
                List.of());
        final var pendingGeneration = pendingIndexWrites.mark(projectName, collName, deletedIds);
        pendingIndexWrites.writeWhilePending(projectName, collName, deletedIds, pendingGeneration,
                () -> fs.writeBatch(projectName, collName, List.of(), DbEntry::getPage, _ -> null, rows));
        final var freed = new TreeMap<Long, long[]>();
        for (final var row : rows) {
            final var page = freed.computeIfAbsent(row.getPage(), _ -> new long[2]);
            page[0] += row.getLength();
            page[1]++;
        }
        freed.forEach((page, bytesAndRows) -> cache.updatePageSizeInMemory(projectName, collName, page,
                -bytesAndRows[0], (int) -bytesAndRows[1]));
        final var primaryKeyIndex = pkRows.editable();
        if (primaryKeyIndex != null) {
            PkIndexEdits.removeAll(primaryKeyIndex, deletedIds);
        }
        for (final var id : deletedIds) {
            cache.evictEntry(projectName, collName, id);
        }
        fs.vacuumPagesAfterDelete(projectName, collName, freed.keySet(), pkRows.vacuumRows(),
                cache.pageOccupancy(projectName, collName));
        final var incarnation = CollectionIncarnation.current(projectName, collName);
        for (final var row : rows) {
            taskManager.submitBackgroundTask(new EntityEvent(
                    EventType.DELETED, projectName, collName, new EntityRef(row.getValue(), row.getPage(),
                            row.getLength(), 0L, priors == null ? null : priors.get(row.getValue())),
                    incarnation, pendingGeneration));
        }
        sink.markDirty(projectName, collName);
        if (populateCache) {
            CollectionAccessHelper.recordCollectionAccess(projectName, collName);
        }
        return deletedIds;
    }

    private static List<PkIndexEntry> rowsOf(WriterPkRows pkRows, Collection<String> ids) {
        final var rows = new ArrayList<PkIndexEntry>(ids.size());
        for (final var id : ids) {
            final var row = pkRows.row(id);
            if (row != null) {
                rows.add(row);
            }
        }
        return rows;
    }

    private static DbEntry onPage(DbEntry entry, long page) {
        if (entry.getPage() == page) {
            return entry;
        }
        final var copy = DbEntry.fromJsonObject(entry.getProjectName(), entry.getCollectionName(), entry.getData());
        copy.set_id(entry.get_id());
        copy.setPage(page);
        copy.setVersion(entry.getVersion());
        return copy;
    }
}
