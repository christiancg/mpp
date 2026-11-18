package org.ccg.mpp.unit.ops.req;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.req.DropCollectionRequest;
import org.junit.jupiter.api.Test;

public class DropCollectionRequestTest {
    @Test
    public void test_constructor_sets_drop_collection_type() {
        String dbName = "testDb";
        String collName = "testCollection";

        DropCollectionRequest request = new DropCollectionRequest(dbName, collName);

        assertEquals(OperationType.DROP_COLLECTION, request.getType());
        assertEquals(dbName, request.getDatabaseName());
        assertEquals(collName, request.getCollectionName());
    }

    @Test
    public void test_constructor_accepts_empty_database_name() {
        String dbName = "";
        String collName = "testCollection";

        DropCollectionRequest request = new DropCollectionRequest(dbName, collName);

        assertEquals("", request.getDatabaseName());
        assertEquals(collName, request.getCollectionName());
        assertEquals(OperationType.DROP_COLLECTION, request.getType());
    }
}
