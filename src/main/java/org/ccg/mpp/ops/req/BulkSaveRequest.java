package org.ccg.mpp.ops.req;

import java.util.List;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ops.OperationType;

public class BulkSaveRequest extends OperationRequest {
    public List<JsonObject> objects;
    private transient boolean backgroundFollowOn;

    public BulkSaveRequest(String projectName, String collectionName) {
        super(OperationType.BULK_SAVE, projectName, collectionName);
    }

    public List<JsonObject> getObjects() {
        return objects;
    }

    public void setObjects(List<JsonObject> objects) {
        this.objects = objects;
    }

    public boolean isBackgroundFollowOn() {
        return backgroundFollowOn;
    }

    public void setBackgroundFollowOn(boolean backgroundFollowOn) {
        this.backgroundFollowOn = backgroundFollowOn;
    }
}
