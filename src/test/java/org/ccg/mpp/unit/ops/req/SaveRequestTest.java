package org.ccg.mpp.unit.ops.req;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.req.SaveRequest;
import org.junit.jupiter.api.Test;

public class SaveRequestTest {
    @Test
    public void test_create_save_request_with_valid_names() {
        String dbName = "testDb";
        String collName = "testCollection";

        SaveRequest saveRequest = new SaveRequest(dbName, collName);

        assertEquals(OperationType.SAVE, saveRequest.getType());
        assertEquals(dbName, saveRequest.getDatabaseName());
        assertEquals(collName, saveRequest.getCollectionName());
        assertNull(saveRequest.get_id());
        assertNull(saveRequest.getObject());
    }

    @Test
    public void test_create_save_request_with_empty_db_name() {
        String dbName = "";
        String collName = "testCollection";

        SaveRequest saveRequest = new SaveRequest(dbName, collName);

        assertEquals(OperationType.SAVE, saveRequest.getType());
        assertEquals("", saveRequest.getDatabaseName());
        assertEquals(collName, saveRequest.getCollectionName());
        assertNull(saveRequest.get_id());
        assertNull(saveRequest.getObject());
    }

    @Test
    public void test_getters_and_setters() {
        String dbName = "testDb";
        String collName = "testCollection";
        String id = "12345";
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("key", "value");

        SaveRequest saveRequest = new SaveRequest(dbName, collName);
        saveRequest.set_id(id);
        saveRequest.setObject(jsonObject);

        assertEquals(OperationType.SAVE, saveRequest.getType());
        assertEquals(dbName, saveRequest.getDatabaseName());
        assertEquals(collName, saveRequest.getCollectionName());
        assertEquals(id, saveRequest.get_id());
        assertEquals(jsonObject, saveRequest.getObject());
    }
}
