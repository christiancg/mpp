package org.ccg.mpp.unit.ops.req;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.req.ListProjectsRequest;
import org.junit.jupiter.api.Test;

public class ListProjectsRequestTest {
    @Test
    public void test_constructor_sets_list_projects_operation_type() {
        ListProjectsRequest request = new ListProjectsRequest();

        assertEquals(OperationType.LIST_PROJECTS, request.getType());
        assertNull(request.getProjectName());
        assertNull(request.getCollectionName());
    }
}
