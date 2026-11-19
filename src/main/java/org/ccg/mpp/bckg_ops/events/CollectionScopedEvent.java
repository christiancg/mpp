package org.ccg.mpp.bckg_ops.events;

import java.util.Objects;

public abstract class CollectionScopedEvent extends Event {
    private final String projectName;
    private final String collName;
    private final long incarnation;
    private final long pendingGeneration;

    protected CollectionScopedEvent(EventType type, String projectName, String collName) {
        this(type, projectName, collName, 0L);
    }

    protected CollectionScopedEvent(EventType type, String projectName, String collName, long incarnation) {
        this(type, projectName, collName, incarnation, 0L);
    }

    protected CollectionScopedEvent(EventType type, String projectName, String collName, long incarnation,
            long pendingGeneration) {
        super(type);
        this.projectName = projectName;
        this.collName = collName;
        this.incarnation = incarnation;
        this.pendingGeneration = pendingGeneration;
    }

    public String getProjectName() {
        return projectName;
    }

    public String getCollName() {
        return collName;
    }

    public long getIncarnation() {
        return incarnation;
    }

    public long getPendingGeneration() {
        return pendingGeneration;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof CollectionScopedEvent that))
            return false;
        if (!super.equals(o))
            return false;
        return incarnation == that.incarnation && pendingGeneration == that.pendingGeneration
                && Objects.equals(projectName, that.projectName) && Objects.equals(collName, that.collName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), projectName, collName, incarnation, pendingGeneration);
    }
}
