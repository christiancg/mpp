package org.ccg.mpp.data.admin;

public enum TriggerRunStatus {
    PENDING, DEAD, STAGED;

    public TriggerRunStatus reported() {
        return this == STAGED ? PENDING : this;
    }
}
