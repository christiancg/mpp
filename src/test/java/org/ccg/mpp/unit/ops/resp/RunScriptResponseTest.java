package org.ccg.mpp.unit.ops.resp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.ccg.mpp.ejson.EJson;
import org.ccg.mpp.ejson.elements.JsonArray;
import org.ccg.mpp.ejson.elements.JsonNumber;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.resp.RunScriptResponse;
import org.junit.jupiter.api.Test;

public class RunScriptResponseTest {
    private final EJson eJson = IocContainer.get(EJson.class);

    @Test
    public void test_success_constructor_sets_ok_status_and_no_error_code() {
        final var response = new RunScriptResponse("ok", new JsonNumber(2), List.of("logged"), false, "run-1");
        assertEquals(OperationType.RUN_SCRIPT, response.getType());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertNull(response.getErrorCode());
        assertEquals(2, response.getResult().asJsonNumber().getValue().intValue());
        assertEquals(List.of("logged"), response.getLogs());
        assertFalse(response.isLogsTruncated());
    }

    @Test
    public void test_error_constructor_keeps_logs_and_sets_error_code() {
        final var response = new RunScriptResponse("TypeError: boom", ErrorCode.SCRIPT_FAILED, List.of("before"), true,
                "run-1");
        assertEquals(OperationStatus.ERROR, response.getStatus());
        assertEquals(ErrorCode.SCRIPT_FAILED.getCode(), response.getErrorCode());
        assertEquals("TypeError: boom", response.getMessage());
        assertNull(response.getResult());
        assertEquals(List.of("before"), response.getLogs());
        assertTrue(response.isLogsTruncated());
    }

    @Test
    public void test_setters_update_fields() {
        final var response = new RunScriptResponse("ok", new JsonNumber(1), List.of(), false, "run-1");
        response.setResult(new JsonString("changed"));
        response.setLogs(List.of("a"));
        response.setLogsTruncated(true);
        assertEquals("changed", response.getResult().asJsonString().getValue());
        assertEquals(List.of("a"), response.getLogs());
        assertTrue(response.isLogsTruncated());
    }

    @Test
    public void test_serializes_object_and_array_results() {
        final var array = new JsonArray();
        array.add(new JsonNumber(1));
        array.add(new JsonNumber(2));
        final var json = eJson.toJson(new RunScriptResponse("ok", array, List.of("line"), false, "run-1"));
        assertTrue(json.contains("\"result\":[1,2]"));
        assertTrue(json.contains("\"logs\":[\"line\"]"));
        assertTrue(json.contains("\"logsTruncated\":false"));
        assertTrue(json.contains("\"status\":\"OK\""));
    }

    @Test
    public void test_serializes_null_result_and_error_code() {
        final var json = eJson
                .toJson(new RunScriptResponse("SyntaxError: bad", ErrorCode.SCRIPT_FAILED, List.of(), false, "run-1"));
        assertTrue(json.contains("\"result\":null"));
        assertTrue(json.contains("\"errorCode\":\"400-9\""));
    }

    @Test
    public void test_escapes_newlines_in_logs() {
        final var json = eJson.toJson(new RunScriptResponse("ok", new JsonNumber(1), List.of("a\nb"), false, "run-1"));
        assertTrue(json.contains("\"a\\nb\""));
        assertFalse(json.contains("a\nb"));
    }
}
