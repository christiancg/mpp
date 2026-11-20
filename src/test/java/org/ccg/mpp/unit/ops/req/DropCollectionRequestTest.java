package org.ccg.mpp.unit.ops.req;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.req.DropCollectionRequest;
import org.junit.jupiter.api.Test;

public class DropCollectionRequestTest {
    @Test
    public void test_constructor_sets_drop_collection_type() {
        String projectName = "testProject";
        String collName = "testCollection";

        DropCollectionRequest request = new DropCollectionRequest(projectName, collName);

        assertEquals(OperationType.DROP_COLLECTION, request.getType());
        assertEquals(projectName, request.getProjectName());
        assertEquals(collName, request.getCollectionName());
    }

    @Test
    public void test_constructor_accepts_empty_project_name() {
        String projectName = "";
        String collName = "testCollection";

        DropCollectionRequest request = new DropCollectionRequest(projectName, collName);

        assertEquals("", request.getProjectName());
        assertEquals(collName, request.getCollectionName());
        assertEquals(OperationType.DROP_COLLECTION, request.getType());
    }
}
