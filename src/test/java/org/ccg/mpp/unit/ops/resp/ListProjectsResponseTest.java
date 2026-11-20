package org.ccg.mpp.unit.ops.resp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.resp.ListProjectsResponse;
import org.junit.jupiter.api.Test;

public class ListProjectsResponseTest {
    @Test
    public void test_response_with_ok_status_and_project_list() {
        List<String> projects = List.of("project1", "project2");
        ListProjectsResponse response = new ListProjectsResponse("Ok", projects);

        assertEquals(OperationType.LIST_PROJECTS, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals("Ok", response.getMessage());
        assertEquals(projects, response.getProjects());
    }

    @Test
    public void test_response_with_error_status_null_list() {
        ListProjectsResponse response = new ListProjectsResponse("Error occurred", null);

        assertEquals(OperationType.LIST_PROJECTS, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals("Error occurred", response.getMessage());
        assertNull(response.getProjects());
    }

    @Test
    public void test_response_with_ok_status_and_empty_list() {
        List<String> emptyList = List.of();
        ListProjectsResponse response = new ListProjectsResponse("Ok", emptyList);

        assertEquals(OperationType.LIST_PROJECTS, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals("Ok", response.getMessage());
        assertEquals(emptyList, response.getProjects());
    }
}
