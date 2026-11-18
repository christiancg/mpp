package org.ccg.mpp.unit.ops.req;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.req.BulkSaveRequest;
import org.junit.jupiter.api.Test;

public class BulkSaveRequestTest {
    @Test
    public void create_bulk_save_request_with_valid_names() {
        String dbName = "testDb";
        String collectionName = "testCollection";

        BulkSaveRequest request = new BulkSaveRequest(dbName, collectionName);

        assertEquals(OperationType.BULK_SAVE, request.getType());
        assertEquals(dbName, request.getDatabaseName());
        assertEquals(collectionName, request.getCollectionName());
        assertNull(request.getObjects());
    }

    @Test
    public void create_bulk_save_request_with_empty_db_name() {
        String dbName = "";
        String collectionName = "testCollection";

        BulkSaveRequest request = new BulkSaveRequest(dbName, collectionName);

        assertEquals(OperationType.BULK_SAVE, request.getType());
        assertEquals("", request.getDatabaseName());
        assertEquals(collectionName, request.getCollectionName());
        assertNull(request.getObjects());
    }

    @Test
    public void test_bulk_save_request_getters_and_setters() {
        String dbName = "testDb";
        String collectionName = "testCollection";
        List<JsonObject> jsonObjects = List.of(new JsonObject(), new JsonObject());

        BulkSaveRequest request = new BulkSaveRequest(dbName, collectionName);
        request.setObjects(jsonObjects);

        assertEquals(OperationType.BULK_SAVE, request.getType());
        assertEquals(dbName, request.getDatabaseName());
        assertEquals(collectionName, request.getCollectionName());
        assertEquals(jsonObjects, request.getObjects());
    }
}
