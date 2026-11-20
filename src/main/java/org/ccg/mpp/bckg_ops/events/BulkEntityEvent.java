package org.ccg.mpp.bckg_ops.events;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.ccg.mpp.data.DbEntry;
import org.ccg.mpp.ops.index.IndexedValue;

public class BulkEntityEvent extends CollectionScopedEvent {
    private final List<EntityRef> inserted;
    private final List<EntityRef> updated;

    public BulkEntityEvent(String projectName, String collName, List<DbEntry> insertedEntries,
            List<DbEntry> updatedEntries) {
        this(projectName, collName, insertedEntries, updatedEntries, 0L);
    }

    public BulkEntityEvent(String projectName, String collName, List<DbEntry> insertedEntries,
            List<DbEntry> updatedEntries, long incarnation) {
        this(projectName, collName, insertedEntries, updatedEntries, incarnation, 0L);
    }

    public BulkEntityEvent(String projectName, String collName, List<DbEntry> insertedEntries,
            List<DbEntry> updatedEntries, long incarnation, long pendingGeneration) {
        this(projectName, collName, insertedEntries, updatedEntries, incarnation, pendingGeneration, null);
    }

    public BulkEntityEvent(String projectName, String collName, List<DbEntry> insertedEntries,
            List<DbEntry> updatedEntries, long incarnation, long pendingGeneration,
            Map<String, Map<String, IndexedValue>> priorsById) {
        super(EventType.CREATED, projectName, collName, incarnation, pendingGeneration);
        this.inserted = EntityRef.allOf(insertedEntries, priorsById);
        this.updated = EntityRef.allOf(updatedEntries, priorsById);
    }

    public List<EntityRef> getInserted() {
        return inserted;
    }

    public List<EntityRef> getUpdated() {
        return updated;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof BulkEntityEvent that))
            return false;
        if (!super.equals(o))
            return false;
        return Objects.equals(inserted, that.inserted) && Objects.equals(updated, that.updated);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), inserted, updated);
    }

    @Override
    public String toString() {
        return "BulkEntityEvent(super=" + super.toString() + ", projectName=" + getProjectName() + ", collName="
                + getCollName() + ", incarnation=" + getIncarnation() + ", pendingGeneration=" + getPendingGeneration()
                + ", inserted=" + inserted + ", updated=" + updated + ")";
    }
}
