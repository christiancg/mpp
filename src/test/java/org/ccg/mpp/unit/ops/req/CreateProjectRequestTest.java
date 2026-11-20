package org.ccg.mpp.unit.ops.req;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.req.CreateProjectRequest;
import org.junit.jupiter.api.Test;

public class CreateProjectRequestTest {
    @Test
    public void test_constructor_sets_create_project_operation_type() {
        String projectName = "testProject";
        CreateProjectRequest request = new CreateProjectRequest(projectName);

        assertEquals(OperationType.CREATE_PROJECT, request.getType());
        assertEquals(projectName, request.getProjectName());
        assertNull(request.getCollectionName());
    }

    @Test
    public void test_constructor_accepts_empty_project_name() {
        String emptyProjectName = "";
        CreateProjectRequest request = new CreateProjectRequest(emptyProjectName);

        assertEquals(OperationType.CREATE_PROJECT, request.getType());
        assertEquals(emptyProjectName, request.getProjectName());
        assertNull(request.getCollectionName());
    }
}
