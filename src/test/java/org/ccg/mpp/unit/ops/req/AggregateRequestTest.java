package org.ccg.mpp.unit.ops.req;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.req.AggregateRequest;
import org.ccg.mpp.ops.req.agg.BaseAggregationStep;
import org.junit.jupiter.api.Test;

public class AggregateRequestTest {
    @Test
    public void test_create_aggregate_request_with_valid_names() {
        String projectName = "testProject";
        String collName = "testCollection";

        AggregateRequest request = new AggregateRequest(projectName, collName);

        assertEquals(OperationType.AGGREGATE, request.getType());
        assertEquals(projectName, request.getProjectName());
        assertEquals(collName, request.getCollectionName());
        assertNull(request.getAggregationSteps());
    }

    @Test
    public void test_create_aggregate_request_with_empty_project_name() {
        String projectName = "";
        String collName = "testCollection";

        AggregateRequest request = new AggregateRequest(projectName, collName);

        assertEquals(OperationType.AGGREGATE, request.getType());
        assertEquals("", request.getProjectName());
        assertEquals(collName, request.getCollectionName());
        assertNull(request.getAggregationSteps());
    }

    @Test
    public void test_getters_and_setters() {
        String projectName = "testProject";
        String collName = "testCollection";
        List<BaseAggregationStep> steps = new ArrayList<>();

        AggregateRequest request = new AggregateRequest(projectName, collName);
        request.setAggregationSteps(steps);

        assertEquals(OperationType.AGGREGATE, request.getType());
        assertEquals(projectName, request.getProjectName());
        assertEquals(collName, request.getCollectionName());
        assertEquals(steps, request.getAggregationSteps());
    }
}
