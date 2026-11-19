package org.ccg.mpp.unit.ops.req;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.req.FindByIdRequest;
import org.junit.jupiter.api.Test;

public class FindByIdRequestTest {
    @Test
    public void constructor_sets_find_by_id_operation_type() {
        String projectName = "testProject";
        String collName = "testCollection";

        FindByIdRequest request = new FindByIdRequest(projectName, collName);

        assertEquals(OperationType.FIND_BY_ID, request.getType());
        assertEquals(projectName, request.getProjectName());
        assertEquals(collName, request.getCollectionName());
    }

    @Test
    public void test_getters_and_setters() {
        String projectName = "testProject";
        String collName = "testCollection";
        String id = "12345";

        FindByIdRequest request = new FindByIdRequest(projectName, collName);
        request.set_id(id);

        assertEquals(id, request.get_id());
        assertEquals(OperationType.FIND_BY_ID, request.getType());
        assertEquals(projectName, request.getProjectName());
        assertEquals(collName, request.getCollectionName());
    }
}
