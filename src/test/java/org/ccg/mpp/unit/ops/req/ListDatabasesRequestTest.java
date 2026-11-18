package org.ccg.mpp.unit.ops.req;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.req.ListDatabasesRequest;
import org.junit.jupiter.api.Test;

public class ListDatabasesRequestTest {
    @Test
    public void test_constructor_sets_list_databases_operation_type() {
        ListDatabasesRequest request = new ListDatabasesRequest();

        assertEquals(OperationType.LIST_DATABASES, request.getType());
        assertNull(request.getDatabaseName());
        assertNull(request.getCollectionName());
    }
}
