package org.ccg.mpp.unit.ops.req;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ex.InvalidCommandException;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.req.AggregateRequest;
import org.ccg.mpp.ops.req.BulkSaveRequest;
import org.ccg.mpp.ops.req.CloseConnectionRequest;
import org.ccg.mpp.ops.req.CreateCollectionRequest;
import org.ccg.mpp.ops.req.CreateIndexRequest;
import org.ccg.mpp.ops.req.CreateProjectRequest;
import org.ccg.mpp.ops.req.DeleteRequest;
import org.ccg.mpp.ops.req.DropCollectionRequest;
import org.ccg.mpp.ops.req.DropIndexRequest;
import org.ccg.mpp.ops.req.DropProjectRequest;
import org.ccg.mpp.ops.req.FindByIdRequest;
import org.ccg.mpp.ops.req.ListCollectionsRequest;
import org.ccg.mpp.ops.req.ListProjectsRequest;
import org.ccg.mpp.ops.req.OperationRequest;
import org.ccg.mpp.ops.req.ReindexRequest;
import org.ccg.mpp.ops.req.RequestParser;
import org.ccg.mpp.ops.req.SaveRequest;
import org.ccg.mpp.ops.req.agg.BaseAggregationStep;
import org.ccg.mpp.ops.req.agg.step.MapAggregationStep;
import org.ccg.mpp.ops.req.agg.step.map.MapOperator;
import org.ccg.mpp.ops.req.agg.step.map.RemoveFieldMapOperator;
import org.ccg.mpp.ops.req.validations.RequestValidator;
import org.junit.jupiter.api.Test;

public class RequestParserTest {
    @Test
    public void test_parse_basic_operation_requests() {
        String saveRequest = """
                {
                    "type": "SAVE",
                    "projectName": "testProject",
                    "collectionName": "testCollection",
                    "object": {
                        "name": "test"
                    }
                }""";

        String deleteRequest = """
                {
                    "type": "DELETE",
                    "projectName": "testProject",
                    "collectionName": "testCollection"
                }""";

        String createProjectRequest = """
                {
                    "type": "CREATE_PROJECT",
                    "projectName": "newProject"
                }""";

        OperationRequest saveResult = RequestParser.parseRequest(saveRequest);
        OperationRequest deleteResult = RequestParser.parseRequest(deleteRequest);
        OperationRequest createProjectResult = RequestParser.parseRequest(createProjectRequest);

        assertInstanceOf(SaveRequest.class, saveResult);
        assertEquals(OperationType.SAVE, saveResult.getType());
        assertEquals("testProject", saveResult.getProjectName());

        assertInstanceOf(DeleteRequest.class, deleteResult);
        assertEquals(OperationType.DELETE, deleteResult.getType());
        assertEquals("testProject", deleteResult.getProjectName());

        assertInstanceOf(CreateProjectRequest.class, createProjectResult);
        assertEquals(OperationType.CREATE_PROJECT, createProjectResult.getType());
        assertEquals("newProject", createProjectResult.getProjectName());
    }

    @Test
    public void test_parse_list_projects_request() {
        String listProjectsRequest = """
                {
                    "type": "LIST_PROJECTS"
                }""";

        OperationRequest result = RequestParser.parseRequest(listProjectsRequest);

        assertInstanceOf(ListProjectsRequest.class, result);
        assertEquals(OperationType.LIST_PROJECTS, result.getType());
        assertNull(result.getProjectName());
        assertNull(result.getCollectionName());
    }

    @Test
    public void test_handle_null_json_fields() {
        String requestWithNulls = """
                {
                    "type": "SAVE",
                    "projectName": "testProject",
                    "collectionName": "testCollection",
                    "object": {
                        "name": null,
                        "age": null,
                        "address": null
                    }
                }""";

        OperationRequest result = RequestParser.parseRequest(requestWithNulls);

        assertInstanceOf(SaveRequest.class, result);
        SaveRequest saveRequest = (SaveRequest) result;
        JsonObject obj = saveRequest.getObject();

        assertTrue(obj.get("name").isJsonNull());
        assertTrue(obj.get("age").isJsonNull());
        assertTrue(obj.get("address").isJsonNull());
    }

    @Test
    public void test_process_requests_with_primary_key_field() {
        String jsonMessage = """
                    {
                        "type": "SAVE",
                        "projectName": "testProject",
                        "collectionName": "testCollection",
                        "object": {
                            "_id": "12345",
                            "name": "test"
                        }
                    }
                """;
        OperationRequest request = RequestParser.parseRequest(jsonMessage);
        assertInstanceOf(SaveRequest.class, request);
        SaveRequest saveRequest = (SaveRequest) request;
        assertEquals("12345", saveRequest.get_id());
        JsonObject object = saveRequest.getObject();
        assertTrue(object.has("_id"));
        assertEquals("12345", object.get("_id").asJsonString().getValue());
    }

    @Test
    public void test_casting_operations() {
        String message = "{ \"type\": \"AGGREGATE\", \"projectName\": \"testProject\", \"collectionName\": \"testCollection\", \"aggregationSteps\": [{\"type\": \"MAP\", \"operators\": [{ \"type\": \"CAST\", \"fieldName\": \"age\", \"toType\": \"STRING\" }]}] }";
        AggregateRequest request = (AggregateRequest) RequestParser.parseRequest(message);
        assertNotNull(request);
        assertEquals(1, request.getAggregationSteps().size());
        BaseAggregationStep step = request.getAggregationSteps().getFirst();
        assertInstanceOf(MapAggregationStep.class, step);
        MapAggregationStep mapStep = (MapAggregationStep) step;
        assertEquals(1, mapStep.getOperators().size());
        MapOperator operator = mapStep.getOperators().getFirst();
        RemoveFieldMapOperator addFieldOperator = (RemoveFieldMapOperator) operator;
        assertEquals("age", addFieldOperator.getFieldName());
    }

    @Test
    public void test_parse_list_collections_request() {
        String listCollectionsRequest = """
                {
                    "type": "LIST_COLLECTIONS",
                    "projectName": "testProject"
                }""";

        OperationRequest result = RequestParser.parseRequest(listCollectionsRequest);

        assertInstanceOf(ListCollectionsRequest.class, result);
        assertEquals(OperationType.LIST_COLLECTIONS, result.getType());
        assertEquals("testProject", result.getProjectName());
        assertNull(result.getCollectionName());
    }

    @Test
    public void test_parse_bulk_save_request() {
        String msg = """
                {"type":"BULK_SAVE","projectName":"db","collectionName":"coll","objects":[{"name":"a"}]}""";
        OperationRequest result = RequestParser.parseRequest(msg);
        assertInstanceOf(BulkSaveRequest.class, result);
        assertEquals(OperationType.BULK_SAVE, result.getType());
    }

    @Test
    public void test_parse_find_by_id_request() {
        String msg = """
                {"type":"FIND_BY_ID","projectName":"db","collectionName":"coll","_id":"123"}""";
        OperationRequest result = RequestParser.parseRequest(msg);
        assertInstanceOf(FindByIdRequest.class, result);
        assertEquals(OperationType.FIND_BY_ID, result.getType());
    }

    @Test
    public void test_parse_drop_project_request() {
        String msg = """
                {"type":"DROP_PROJECT","projectName":"myProject"}""";
        OperationRequest result = RequestParser.parseRequest(msg);
        assertInstanceOf(DropProjectRequest.class, result);
        assertEquals(OperationType.DROP_PROJECT, result.getType());
        assertEquals("myProject", result.getProjectName());
    }

    @Test
    public void test_parse_drop_collection_request() {
        String msg = """
                {"type":"DROP_COLLECTION","projectName":"db","collectionName":"coll"}""";
        OperationRequest result = RequestParser.parseRequest(msg);
        assertInstanceOf(DropCollectionRequest.class, result);
        assertEquals(OperationType.DROP_COLLECTION, result.getType());
    }

    @Test
    public void test_parse_create_index_request() {
        String msg = """
                {"type":"CREATE_INDEX","projectName":"db","collectionName":"coll","fieldName":"myField"}""";
        OperationRequest result = RequestParser.parseRequest(msg);
        assertInstanceOf(CreateIndexRequest.class, result);
        assertEquals(OperationType.CREATE_INDEX, result.getType());
    }

    @Test
    public void test_create_index_field_names_parsed() {
        String msg = """
                {"type":"CREATE_INDEX","projectName":"shop","collectionName":"orders","fieldNames":["a","b.c"]}""";
        OperationRequest result = RequestParser.parseRequest(msg);
        assertInstanceOf(CreateIndexRequest.class, result);
        final var request = (CreateIndexRequest) result;
        assertEquals(List.of("a", "b.c"), request.getFieldNames());
        assertEquals(List.of("a", "b.c"), request.targetFieldNames());
        assertTrue(RequestValidator.validate(request).isValid());
    }

    @Test
    public void test_empty_field_names_refused() {
        String msg = """
                {"type":"CREATE_INDEX","projectName":"shop","collectionName":"orders","fieldNames":[]}""";
        final var validation = RequestValidator.validate(RequestParser.parseRequest(msg));
        assertFalse(validation.isValid());
        assertTrue(validation.getErrorMessage().contains("fieldNames must not be empty"), validation.getErrorMessage());
    }

    @Test
    public void test_one_invalid_field_name_refuses_the_whole_request() {
        for (final var names : List.of("[\"a\",\"bad name\"]", "[\"a\",\"\"]", "[\"a\",\"_id\"]", "[\"a\",null]")) {
            String msg = "{\"type\":\"CREATE_INDEX\",\"projectName\":\"shop\",\"collectionName\":\"orders\","
                    + "\"fieldNames\":" + names + "}";
            assertFalse(RequestValidator.validate(RequestParser.parseRequest(msg)).isValid(), names);
        }
        String both = """
                {"type":"CREATE_INDEX","projectName":"shop","collectionName":"orders","fieldName":" ","fieldNames":["a"]}""";
        assertFalse(RequestValidator.validate(RequestParser.parseRequest(both)).isValid());
    }

    @Test
    public void test_parse_drop_index_request() {
        String msg = """
                {"type":"DROP_INDEX","projectName":"db","collectionName":"coll","fieldName":"myField"}""";
        OperationRequest result = RequestParser.parseRequest(msg);
        assertInstanceOf(DropIndexRequest.class, result);
        assertEquals(OperationType.DROP_INDEX, result.getType());
    }

    @Test
    public void test_parse_reindex_request_with_field_names() {
        String msg = """
                {"type":"REINDEX","projectName":"db","collectionName":"coll","fieldNames":["email","status"]}""";
        OperationRequest result = RequestParser.parseRequest(msg);
        assertInstanceOf(ReindexRequest.class, result);
        assertEquals(OperationType.REINDEX, result.getType());
        assertEquals(List.of("email", "status"), ((ReindexRequest) result).getFieldNames());
    }

    @Test
    public void test_parse_reindex_request_without_field_names() {
        String msg = """
                {"type":"REINDEX","projectName":"db","collectionName":"coll"}""";
        OperationRequest result = RequestParser.parseRequest(msg);
        assertInstanceOf(ReindexRequest.class, result);
        assertTrue(((ReindexRequest) result).getFieldNames().isEmpty());
    }

    @Test
    public void test_parse_close_connection_request() {
        String msg = """
                {"type":"CLOSE_CONNECTION"}""";
        OperationRequest result = RequestParser.parseRequest(msg);
        assertInstanceOf(CloseConnectionRequest.class, result);
        assertEquals(OperationType.CLOSE_CONNECTION, result.getType());
    }

    @Test
    public void test_parse_create_collection_request() {
        String msg = """
                {"type":"CREATE_COLLECTION","projectName":"db","collectionName":"coll"}""";
        OperationRequest result = RequestParser.parseRequest(msg);
        assertInstanceOf(CreateCollectionRequest.class, result);
        assertEquals(OperationType.CREATE_COLLECTION, result.getType());
    }

    @Test
    public void test_save_with_overflowing_number_is_an_invalid_command() {
        final var message = "{\"type\":\"SAVE\",\"projectName\":\"myProject\",\"collectionName\":\"myCollection\","
                + "\"object\":{\"_id\":\"a1\",\"v\":1e400}}";
        assertThrows(InvalidCommandException.class, () -> RequestParser.parseRequest(message));
    }

    @Test
    public void test_save_with_a_representable_number_still_parses() {
        final var message = "{\"type\":\"SAVE\",\"projectName\":\"myProject\",\"collectionName\":\"myCollection\","
                + "\"object\":{\"_id\":\"a1\",\"v\":1.7976931348623157e308}}";
        assertEquals(OperationType.SAVE, RequestParser.parseRequest(message).getType());
    }

    @Test
    public void test_parse_request_with_a_budget_wraps_the_refusal_as_an_invalid_command() {
        final var message = "{\"type\":\"LIST_PROJECTS\",\"x\":[" + "1,".repeat(50) + "1]}";

        final var refusal = assertThrows(InvalidCommandException.class, () -> RequestParser.parseRequest(message, 20));

        assertEquals("The command is not valid", refusal.getMessage());
        assertEquals(OperationType.LIST_PROJECTS, RequestParser.parseRequest(message, 1_000).getType());
    }

    @Test
    public void test_a_missing_type_is_an_invalid_command() {
        assertThrows(InvalidCommandException.class, () -> RequestParser.parseRequest("{\"projectName\":\"d\"}"));
    }

    @Test
    public void test_a_non_string_type_is_an_invalid_command() {
        assertThrows(InvalidCommandException.class, () -> RequestParser.parseRequest("{\"type\":5}"));
    }

    @Test
    public void test_an_invalid_command_names_its_type_only_once_the_type_parsed() {
        final var unparsed = assertThrows(InvalidCommandException.class,
                () -> RequestParser.parseRequest("{\"type\":"));
        assertNull(unparsed.getRequestType());
        final var unknownType = assertThrows(InvalidCommandException.class,
                () -> RequestParser.parseRequest("{\"type\":\"NOT_A_TYPE\"}"));
        assertNull(unknownType.getRequestType());
        final var badBody = assertThrows(InvalidCommandException.class, () -> RequestParser.parseRequest(
                "{\"type\":\"AGGREGATE\",\"projectName\":\"d\",\"collectionName\":\"c\",\"aggregationSteps\":5}"));
        assertEquals(OperationType.AGGREGATE, badBody.getRequestType());
    }

    @Test
    public void test_an_unknown_type_is_an_invalid_command() {
        assertThrows(InvalidCommandException.class, () -> RequestParser.parseRequest("{\"type\":\"NOT_A_TYPE\"}"));
    }

    @Test
    public void test_save_still_takes_its_id_from_the_object() {
        final var request = RequestParser.parseRequest(
                "{\"type\":\"SAVE\",\"projectName\":\"testProject\",\"collectionName\":\"c\",\"object\":{\"_id\":\"abc\",\"n\":1}}");

        assertEquals("abc", ((SaveRequest) request).get_id());
    }
}
