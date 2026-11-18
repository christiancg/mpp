package org.ccg.mpp.unit.ops.req;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.req.CreateDatabaseRequest;
import org.junit.jupiter.api.Test;

public class CreateDatabaseRequestTest {
    @Test
    public void test_constructor_sets_create_database_operation_type() {
        String dbName = "testDb";
        CreateDatabaseRequest request = new CreateDatabaseRequest(dbName);

        assertEquals(OperationType.CREATE_DATABASE, request.getType());
        assertEquals(dbName, request.getDatabaseName());
        assertNull(request.getCollectionName());
    }

    @Test
    public void test_constructor_accepts_empty_database_name() {
        String emptyDbName = "";
        CreateDatabaseRequest request = new CreateDatabaseRequest(emptyDbName);

        assertEquals(OperationType.CREATE_DATABASE, request.getType());
        assertEquals(emptyDbName, request.getDatabaseName());
        assertNull(request.getCollectionName());
    }
}
