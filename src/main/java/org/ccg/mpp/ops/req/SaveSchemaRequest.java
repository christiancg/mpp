package org.ccg.mpp.ops.req;

import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ops.OperationType;

public class SaveSchemaRequest extends OperationRequest {
    private JsonObject schema;

    public SaveSchemaRequest(String projectName, String collectionName, JsonObject schema) {
        super(OperationType.SAVE_SCHEMA, projectName, collectionName);
        this.schema = schema;
    }

    public JsonObject getSchema() {
        return schema;
    }

    public void setSchema(JsonObject schema) {
        this.schema = schema;
    }
}
