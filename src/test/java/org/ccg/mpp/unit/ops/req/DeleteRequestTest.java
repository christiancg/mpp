package org.ccg.mpp.unit.ops.req;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.req.DeleteRequest;
import org.junit.jupiter.api.Test;

public class DeleteRequestTest {
    @Test
    public void test_constructor_sets_delete_operation_type() {
        DeleteRequest deleteRequest = new DeleteRequest("testProject", "testCollection");

        assertEquals(OperationType.DELETE, deleteRequest.getType());
        assertEquals("testProject", deleteRequest.getProjectName());
        assertEquals("testCollection", deleteRequest.getCollectionName());
    }

    @Test
    public void test_getters_and_setters() {
        DeleteRequest deleteRequest = new DeleteRequest("testProject", "testCollection");
        deleteRequest.set_id("12345");

        assertEquals("12345", deleteRequest.get_id());
        assertEquals(OperationType.DELETE, deleteRequest.getType());
        assertEquals("testProject", deleteRequest.getProjectName());
        assertEquals("testCollection", deleteRequest.getCollectionName());
    }
}
