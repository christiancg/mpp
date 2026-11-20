package org.ccg.mpp.unit.ops.req;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.req.DeleteSchemaRequest;
import org.junit.jupiter.api.Test;

public class DeleteSchemaRequestTest {
    @Test
    public void test_constructor_and_getters() {
        final var request = new DeleteSchemaRequest("testProject", "testColl");
        assertEquals(OperationType.DELETE_SCHEMA, request.getType());
        assertEquals("testProject", request.getProjectName());
        assertEquals("testColl", request.getCollectionName());
    }
}
