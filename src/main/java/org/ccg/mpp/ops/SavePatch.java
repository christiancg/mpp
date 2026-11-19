package org.ccg.mpp.ops;

import java.util.List;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ops.req.BulkSaveRequest;
import org.ccg.mpp.ops.req.SaveRequest;

public final class SavePatch {
    private SavePatch() {
    }

    public static JsonObject merged(JsonObject stored, JsonObject patch) {
        final var merged = stored.deepCopy();
        for (final var property : patch.entrySet()) {
            merged.add(property.getKey(), property.getValue().deepCopy());
        }
        return merged;
    }

    public static BulkSaveRequest bulkSaveOf(SaveRequest request, List<JsonObject> mergedDocuments) {
        final var bulk = new BulkSaveRequest(request.getProjectName(), request.getCollectionName());
        bulk.setObjects(mergedDocuments);
        bulk.setTriggerDepth(request.getTriggerDepth());
        bulk.setBackgroundFollowOn(request.isBackgroundFollowOn());
        return bulk;
    }
}
