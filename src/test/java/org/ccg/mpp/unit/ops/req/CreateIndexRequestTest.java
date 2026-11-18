package org.ccg.mpp.unit.ops.req;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.req.CreateIndexRequest;
import org.junit.jupiter.api.Test;

public class CreateIndexRequestTest {
    @Test
    public void test_constructor_sets_create_index_operation_type() {
        String dbName = "testDb";
        String collName = "testColl";
        String fieldName = "testField";

        CreateIndexRequest request = new CreateIndexRequest(dbName, collName, fieldName);

        assertEquals(OperationType.CREATE_INDEX, request.getType());
    }

    @Test
    public void test_getters_and_setters() {
        String dbName = "testDb";
        String collName = "testColl";
        String fieldName = "testField";

        CreateIndexRequest request = new CreateIndexRequest(dbName, collName, fieldName);

        assertEquals(dbName, request.getDatabaseName());
        assertEquals(collName, request.getCollectionName());
        assertEquals(fieldName, request.getFieldName());

        String newFieldName = "newTestField";
        request.setFieldName(newFieldName);
        assertEquals(newFieldName, request.getFieldName());
    }
}
