package org.ccg.mpp.ops.req;

import org.ccg.mpp.ops.OperationType;

public abstract class VersionedDefinitionRequest extends OperationRequest {
    private Boolean enabled;
    private Long ifVersion;

    protected VersionedDefinitionRequest(OperationType type, String databaseName, String collectionName) {
        super(type, databaseName, collectionName);
    }

    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Long getIfVersion() {
        return ifVersion;
    }

    public void setIfVersion(Long ifVersion) {
        this.ifVersion = ifVersion;
    }

    public boolean conflictsWith(long existingVersion) {
        return ifVersion != null && ifVersion != existingVersion;
    }
}
