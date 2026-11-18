package org.ccg.mpp.unit.ops.resp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.resp.SaveResponse;
import org.junit.jupiter.api.Test;

public class SaveResponseTest {
    @Test
    public void test_create_save_response_with_valid_data() {
        String id = "123abc";
        String message = "Document saved successfully";

        SaveResponse response = new SaveResponse(message, id);

        assertEquals(OperationType.SAVE, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(message, response.getMessage());
        assertEquals(id, response.get_id());
    }

    @Test
    public void test_create_save_response_with_null_id() {
        String message = "Document saved with null ID";
        SaveResponse response = new SaveResponse(message, null);

        assertEquals(OperationType.SAVE, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(message, response.getMessage());
        assertNull(response.get_id());
    }

    @Test
    public void test_getters_and_setters() {
        String id = "123abc";
        String message = "Document saved successfully";
        SaveResponse response = new SaveResponse(message, id);

        assertEquals(OperationType.SAVE, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(message, response.getMessage());
        assertEquals(id, response.get_id());

        String newId = "456def";
        response.set_id(newId);

        assertEquals(newId, response.get_id());
    }
}
