package org.ccg.mpp.unit.ops.req;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.req.DropIndexRequest;
import org.junit.jupiter.api.Test;

public class DropIndexRequestTest {
    @Test
    public void test_create_drop_index_request_with_valid_params() {
        String projectName = "testProject";
        String collectionName = "testCollection";
        String fieldName = "testField";

        DropIndexRequest request = new DropIndexRequest(projectName, collectionName, fieldName);

        assertEquals(OperationType.DROP_INDEX, request.getType());
        assertEquals(projectName, request.getProjectName());
        assertEquals(collectionName, request.getCollectionName());
        assertEquals(fieldName, request.getFieldName());
    }

    @Test
    public void test_getters_and_setters() {
        String projectName = "testProject";
        String collectionName = "testCollection";
        String fieldName = "testField";

        DropIndexRequest request = new DropIndexRequest(projectName, collectionName, fieldName);

        assertEquals(OperationType.DROP_INDEX, request.getType());
        assertEquals(projectName, request.getProjectName());
        assertEquals(collectionName, request.getCollectionName());
        assertEquals(fieldName, request.getFieldName());

        String newFieldName = "newTestField";
        request.setFieldName(newFieldName);
        assertEquals(newFieldName, request.getFieldName());
    }
}
