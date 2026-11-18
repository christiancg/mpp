package org.ccg.mpp.unit.ops.resp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.resp.DeleteResponse;
import org.junit.jupiter.api.Test;

public class DeleteResponseTest {
    @Test
    public void test_create_delete_response_with_ok_status_and_message() {
        String successMessage = "Successfully deleted";
        DeleteResponse response = new DeleteResponse(successMessage);

        assertEquals(OperationType.DELETE, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(successMessage, response.getMessage());
    }

    @Test
    public void test_create_delete_response_with_null_message() {
        DeleteResponse response = new DeleteResponse(null);

        assertEquals(OperationType.DELETE, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertNull(response.getMessage());
    }
}
