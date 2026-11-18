package org.ccg.mpp.unit.ops.resp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.resp.AggregateResponse;
import org.junit.jupiter.api.Test;

public class AggregateResponseTest {
    @Test
    public void test_create_aggregate_response_with_valid_results() {
        List<JsonObject> results = new ArrayList<>();
        JsonObject obj1 = new JsonObject();
        obj1.addProperty("field1", "value1");
        JsonObject obj2 = new JsonObject();
        obj2.addProperty("field2", 123);
        results.add(obj1);
        results.add(obj2);

        AggregateResponse response = new AggregateResponse("Success", results);

        assertEquals(OperationType.AGGREGATE, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals("Success", response.getMessage());
        assertEquals(2, response.getResults().size());
        assertEquals(results, response.getResults());
    }

    @Test
    public void test_create_aggregate_response_with_null_results() {
        AggregateResponse response = new AggregateResponse("No results found", null);

        assertEquals(OperationType.AGGREGATE, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals("No results found", response.getMessage());
        assertNull(response.getResults());
    }

    @Test
    public void test_create_with_empty_results_and_ok_status() {
        List<JsonObject> emptyResults = new ArrayList<>();
        AggregateResponse response = new AggregateResponse("Success", emptyResults);
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals("Success", response.getMessage());
        assertTrue(response.getResults().isEmpty());
    }

    @Test
    public void test_operation_type_is_aggregate() {
        List<JsonObject> results = new ArrayList<>();
        AggregateResponse response = new AggregateResponse("Success", results);
        assertEquals(OperationType.AGGREGATE, response.getType());
    }

    @Test
    public void test_getters_and_setters() {
        List<JsonObject> results = new ArrayList<>();
        JsonObject obj1 = new JsonObject();
        obj1.addProperty("field1", "value1");
        JsonObject obj2 = new JsonObject();
        obj2.addProperty("field2", 123);
        results.add(obj1);
        results.add(obj2);

        AggregateResponse response = new AggregateResponse("Success", results);

        assertEquals(OperationType.AGGREGATE, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals("Success", response.getMessage());
        assertEquals(results, response.getResults());

        response.setResults(new ArrayList<>());
        assertEquals(0, response.getResults().size());
    }
}
