package org.ccg.mpp.unit.ops.req;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.req.OperationRequest;
import org.junit.jupiter.api.Test;

public class OperationRequestTest {
    @Test
    public void test_create_operation_request_with_valid_params() {
        OperationType type = OperationType.SAVE;
        String projectName = "testProject";
        String collName = "testCollection";

        OperationRequest request = new OperationRequest(type, projectName, collName);

        assertEquals(type, request.getType());
        assertEquals(projectName, request.getProjectName());
        assertEquals(collName, request.getCollectionName());
    }

    @Test
    public void test_operation_request_getters_and_setters() {
        OperationType type = OperationType.FIND_BY_ID;
        String projectName = "sampleProject";
        String collName = "sampleCollection";

        OperationRequest request = new OperationRequest(type, projectName, collName);

        assertEquals(type, request.getType());
        assertEquals(projectName, request.getProjectName());
        assertEquals(collName, request.getCollectionName());
    }
}
