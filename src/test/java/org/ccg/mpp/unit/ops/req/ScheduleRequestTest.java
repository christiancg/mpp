package org.ccg.mpp.unit.ops.req;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.req.DeleteScheduleRequest;
import org.ccg.mpp.ops.req.ListSchedulesRequest;
import org.ccg.mpp.ops.req.RequestParser;
import org.ccg.mpp.ops.req.SaveScheduleRequest;
import org.ccg.mpp.ops.req.validations.RequestValidator;
import org.ccg.mpp.ops.resp.ListSchedulesResponse;
import org.ccg.mpp.ops.resp.OperationResponse;
import org.ccg.mpp.ops.resp.SaveScheduleResponse;
import org.junit.jupiter.api.Test;

public class ScheduleRequestTest {
    @Test
    public void test_save_schedule_request_accessors() {
        final var request = new SaveScheduleRequest();
        assertEquals(OperationType.SAVE_SCHEDULE, request.getType());
        assertTrue(request.getArgs().entrySet().isEmpty());
        request.setName("s");
        request.setProcedureName("p");
        request.setCron("0 3 * * *");
        request.setIntervalMs(2000L);
        request.setTimeoutMs(1000L);
        request.setDescription("nightly");
        request.setIfVersion(4L);
        final var args = new JsonObject();
        args.add("k", new JsonString("v"));
        request.setArgs(args);
        assertEquals("s", request.getName());
        assertEquals("p", request.getProcedureName());
        assertEquals("0 3 * * *", request.getCron());
        assertEquals(2000L, request.getIntervalMs());
        assertEquals(1000L, request.getTimeoutMs());
        assertEquals("nightly", request.getDescription());
        assertEquals(4L, request.getIfVersion());
        assertEquals("v", request.getArgs().get("k").asJsonString().getValue());
        assertTrue(request.isEnabled());
        request.setEnabled(false);
        assertFalse(request.isEnabled());
    }

    @Test
    public void test_delete_and_list_schedule_request_accessors() {
        final var delete = new DeleteScheduleRequest();
        assertEquals(OperationType.DELETE_SCHEDULE, delete.getType());
        delete.setName("s");
        assertEquals("s", delete.getName());
        assertEquals("db", new DeleteScheduleRequest("db", "s").getProjectName());
        assertEquals(OperationType.LIST_SCHEDULES, new ListSchedulesRequest().getType());
        assertEquals("db", new ListSchedulesRequest("db").getProjectName());
    }

    @Test
    public void test_requests_parse_from_the_wire() {
        final var save = (SaveScheduleRequest) RequestParser.parseRequest(
                "{\"type\":\"SAVE_SCHEDULE\",\"projectName\":\"db\",\"name\":\"s\",\"procedureName\":\"p\","
                        + "\"cron\":\"0 3 * * *\",\"args\":{\"days\":1},\"timeoutMs\":60000,\"ifVersion\":2}");
        assertEquals("s", save.getName());
        assertEquals("p", save.getProcedureName());
        assertEquals("0 3 * * *", save.getCron());
        assertEquals(60000L, save.getTimeoutMs());
        assertEquals(2L, save.getIfVersion());
        assertEquals(1d, save.getArgs().get("days").asJsonNumber().getValue().doubleValue());

        assertInstanceOf(DeleteScheduleRequest.class,
                RequestParser.parseRequest("{\"type\":\"DELETE_SCHEDULE\",\"projectName\":\"db\",\"name\":\"s\"}"));
        assertInstanceOf(ListSchedulesRequest.class,
                RequestParser.parseRequest("{\"type\":\"LIST_SCHEDULES\",\"projectName\":\"db\"}"));
    }

    @Test
    public void test_response_accessors() {
        assertEquals(3L, new SaveScheduleResponse("ok", 3L).getVersion());
        assertEquals(OperationType.DELETE_SCHEDULE,
                OperationResponse.ok(OperationType.DELETE_SCHEDULE, "ok").getType());
        assertTrue(new ListSchedulesResponse("ok", List.of()).getSchedules().isEmpty());
    }

    @Test
    public void test_validation_rejects_bad_names_and_projects() {
        final var request = new SaveScheduleRequest("shop", "nightly", "rollup");
        request.setIntervalMs(1000L);
        assertTrue(RequestValidator.validate(request).isValid());

        assertFalse(RequestValidator.validate(new SaveScheduleRequest(null, "nightly", "rollup")).isValid());
        assertFalse(RequestValidator.validate(new SaveScheduleRequest("admin", "nightly", "rollup")).isValid());
        assertFalse(RequestValidator.validate(new SaveScheduleRequest("shop", "x", "rollup")).isValid());
        assertFalse(RequestValidator.validate(new SaveScheduleRequest("shop", "nightly", "x")).isValid());
        assertFalse(RequestValidator.validate(new DeleteScheduleRequest("shop", "x")).isValid());
        assertTrue(RequestValidator.validate(new DeleteScheduleRequest("shop", "nightly")).isValid());
        assertTrue(RequestValidator.validate(new ListSchedulesRequest("shop")).isValid());
        assertFalse(RequestValidator.validate(new ListSchedulesRequest("ad")).isValid());
    }
}
