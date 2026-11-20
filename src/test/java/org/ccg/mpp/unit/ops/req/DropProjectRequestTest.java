package org.ccg.mpp.unit.ops.req;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.req.DropProjectRequest;
import org.junit.jupiter.api.Test;

public class DropProjectRequestTest {
    @Test
    public void test_create_drop_project_request_with_valid_name() {
        String projectName = "test_db";

        DropProjectRequest request = new DropProjectRequest(projectName);

        assertEquals(OperationType.DROP_PROJECT, request.getType());
        assertEquals(projectName, request.getProjectName());
        assertNull(request.getCollectionName());
    }

    @Test
    public void test_create_drop_project_request_with_empty_name() {
        String projectName = "";

        DropProjectRequest request = new DropProjectRequest(projectName);

        assertEquals(OperationType.DROP_PROJECT, request.getType());
        assertEquals(projectName, request.getProjectName());
        assertNull(request.getCollectionName());
    }
}
