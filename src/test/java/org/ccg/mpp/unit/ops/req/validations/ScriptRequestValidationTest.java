package org.ccg.mpp.unit.ops.req.validations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.ccg.mpp.data.MiddlewarePhase;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.req.CallProcedureRequest;
import org.ccg.mpp.ops.req.DeleteEntrypointRequest;
import org.ccg.mpp.ops.req.DeleteMiddlewareRequest;
import org.ccg.mpp.ops.req.DeleteProcedureRequest;
import org.ccg.mpp.ops.req.DeleteScheduleRequest;
import org.ccg.mpp.ops.req.DeleteTriggerRequest;
import org.ccg.mpp.ops.req.InlineProcedure;
import org.ccg.mpp.ops.req.ListEntrypointsRequest;
import org.ccg.mpp.ops.req.ListMiddlewaresRequest;
import org.ccg.mpp.ops.req.ListTriggersRequest;
import org.ccg.mpp.ops.req.RequestParser;
import org.ccg.mpp.ops.req.SaveEntrypointRequest;
import org.ccg.mpp.ops.req.SaveMiddlewareRequest;
import org.ccg.mpp.ops.req.SaveProcedureRequest;
import org.ccg.mpp.ops.req.SaveScheduleRequest;
import org.ccg.mpp.ops.req.SaveTriggerRequest;
import org.ccg.mpp.ops.req.TestTriggerRequest;
import org.ccg.mpp.ops.req.validations.RequestValidator;
import org.junit.jupiter.api.Test;

public class ScriptRequestValidationTest {
    @Test
    public void test_save_procedure_valid() {
        assertTrue(RequestValidator.validate(new SaveProcedureRequest("myProject", "myProc", "return 1;")).isValid());
    }

    @Test
    public void test_save_procedure_rejects_the_admin_project() {
        assertFalse(RequestValidator.validate(new SaveProcedureRequest("admin", "myProc", "return 1;")).isValid());
    }

    @Test
    public void test_save_procedure_rejects_a_name_that_would_escape_its_project() {
        assertFalse(
                RequestValidator.validate(new SaveProcedureRequest("myProject", "../escaped", "return 1;")).isValid());
    }

    @Test
    public void test_save_procedure_rejects_a_missing_name() {
        assertFalse(RequestValidator.validate(new SaveProcedureRequest("myProject", null, "return 1;")).isValid());
    }

    @Test
    public void test_save_procedure_rejects_a_blank_script() {
        assertFalse(RequestValidator.validate(new SaveProcedureRequest("myProject", "myProc", "   ")).isValid());
    }

    @Test
    public void test_delete_procedure_valid() {
        assertTrue(RequestValidator.validate(new DeleteProcedureRequest("myProject", "myProc")).isValid());
    }

    @Test
    public void test_delete_procedure_rejects_a_bad_name() {
        assertFalse(RequestValidator.validate(new DeleteProcedureRequest("myProject", "no")).isValid());
    }

    @Test
    public void test_delete_procedure_rejects_a_bad_project() {
        assertFalse(RequestValidator.validate(new DeleteProcedureRequest("no", "myProc")).isValid());
    }

    @Test
    public void test_call_procedure_valid() {
        assertTrue(
                RequestValidator.validate(new CallProcedureRequest("myProject", "myProc", new JsonObject())).isValid());
    }

    @Test
    public void test_call_procedure_rejects_a_bad_name() {
        assertFalse(
                RequestValidator.validate(new CallProcedureRequest("myProject", "a b", new JsonObject())).isValid());
    }

    @Test
    public void test_save_trigger_valid() {
        final var request = new SaveTriggerRequest("myProject", "myColl", "myTrigger", List.of("CREATED"), "myProc");
        assertTrue(RequestValidator.validate(request).isValid());
    }

    @Test
    public void test_save_trigger_rejects_a_missing_collection() {
        final var request = new SaveTriggerRequest("myProject", null, "myTrigger", List.of("CREATED"), "myProc");
        assertFalse(RequestValidator.validate(request).isValid());
    }

    @Test
    public void test_save_trigger_rejects_a_reserved_collection() {
        final var request = new SaveTriggerRequest("myProject", "script_runs", "myTrigger", List.of("CREATED"),
                "myProc");
        assertFalse(RequestValidator.validate(request).isValid());
    }

    @Test
    public void test_save_trigger_rejects_a_bad_trigger_name() {
        final var request = new SaveTriggerRequest("myProject", "myColl", "x", List.of("CREATED"), "myProc");
        assertFalse(RequestValidator.validate(request).isValid());
    }

    @Test
    public void test_save_trigger_rejects_a_bad_procedure_name() {
        final var request = new SaveTriggerRequest("myProject", "myColl", "myTrigger", List.of("CREATED"), "x");
        assertFalse(RequestValidator.validate(request).isValid());
    }

    @Test
    public void test_delete_trigger_valid() {
        assertTrue(RequestValidator.validate(new DeleteTriggerRequest("myProject", "myColl", "myTrigger")).isValid());
    }

    @Test
    public void test_delete_trigger_rejects_a_bad_collection() {
        assertFalse(RequestValidator.validate(new DeleteTriggerRequest("myProject", "!!", "myTrigger")).isValid());
    }

    @Test
    public void test_delete_trigger_rejects_a_bad_name() {
        assertFalse(RequestValidator.validate(new DeleteTriggerRequest("myProject", "myColl", null)).isValid());
    }

    @Test
    public void test_list_triggers_without_a_collection_is_valid() {
        assertTrue(RequestValidator.validate(new ListTriggersRequest("myProject", null)).isValid());
    }

    @Test
    public void test_list_triggers_with_a_collection_is_valid() {
        assertTrue(RequestValidator.validate(new ListTriggersRequest("myProject", "myColl")).isValid());
    }

    @Test
    public void test_list_triggers_rejects_a_bad_collection() {
        assertFalse(RequestValidator.validate(new ListTriggersRequest("myProject", "??")).isValid());
    }

    @Test
    public void test_list_triggers_rejects_a_bad_project() {
        assertFalse(RequestValidator.validate(new ListTriggersRequest("admin", "myColl")).isValid());
    }

    @Test
    public void test_test_trigger_valid() {
        final var request = new TestTriggerRequest("myProject", "myColl", "myTrigger", "CREATED", new JsonObject());
        assertTrue(RequestValidator.validate(request).isValid());
    }

    @Test
    public void test_test_trigger_rejects_a_missing_document() {
        final var request = new TestTriggerRequest("myProject", "myColl", "myTrigger", "CREATED", null);
        assertFalse(RequestValidator.validate(request).isValid());
    }

    @Test
    public void test_test_trigger_rejects_a_bad_name() {
        final var request = new TestTriggerRequest("myProject", "myColl", "no", "CREATED", new JsonObject());
        assertFalse(RequestValidator.validate(request).isValid());
    }

    @Test
    public void test_test_trigger_rejects_a_bad_collection() {
        final var request = new TestTriggerRequest("myProject", "", "myTrigger", "CREATED", new JsonObject());
        assertFalse(RequestValidator.validate(request).isValid());
    }

    @Test
    public void test_save_schedule_valid() {
        assertTrue(RequestValidator.validate(new SaveScheduleRequest("myProject", "nightly", "myProc")).isValid());
    }

    @Test
    public void test_save_schedule_rejects_a_bad_procedure_name() {
        assertFalse(RequestValidator.validate(new SaveScheduleRequest("myProject", "nightly", "")).isValid());
    }

    @Test
    public void test_save_schedule_rejects_a_bad_project() {
        assertFalse(RequestValidator.validate(new SaveScheduleRequest(null, "nightly", "myProc")).isValid());
    }

    @Test
    public void test_delete_schedule_valid() {
        assertTrue(RequestValidator.validate(new DeleteScheduleRequest("myProject", "nightly")).isValid());
    }

    @Test
    public void test_delete_schedule_rejects_a_bad_name() {
        assertFalse(RequestValidator.validate(new DeleteScheduleRequest("myProject", "n")).isValid());
    }

    @Test
    public void test_delete_schedule_rejects_a_bad_project() {
        assertFalse(RequestValidator.validate(new DeleteScheduleRequest("a b", "nightly")).isValid());
    }

    private static SaveEntrypointRequest entrypoint(String procedureName, InlineProcedure procedure) {
        final var request = new SaveEntrypointRequest("myProject", "getOrder", "GET", "/orders/{id}");
        request.setProcedureName(procedureName);
        request.setProcedure(procedure);
        return request;
    }

    @Test
    public void test_save_entrypoint_needs_exactly_one_procedure_source() {
        assertTrue(RequestValidator.validate(entrypoint("myProc", null)).isValid());
        assertTrue(RequestValidator.validate(entrypoint(null, new InlineProcedure("myProc", "return 1;"))).isValid());
        final var neither = RequestValidator.validate(entrypoint(null, null));
        final var both = RequestValidator.validate(entrypoint("myProc", new InlineProcedure("myProc", "return 1;")));

        assertFalse(neither.isValid());
        assertEquals(ErrorCode.INVALID_ENTRYPOINT, neither.getErrorCode());
        assertEquals(ErrorCode.INVALID_ENTRYPOINT, both.getErrorCode());
    }

    @Test
    public void test_save_entrypoint_refuses_bad_names_and_a_blank_inline_script() {
        assertFalse(RequestValidator.validate(entrypoint(null, new InlineProcedure("myProc", " "))).isValid());
        assertFalse(RequestValidator.validate(entrypoint(null, new InlineProcedure("../x", "return 1;"))).isValid());
        assertFalse(RequestValidator.validate(entrypoint("../x", null)).isValid());
        final var badName = new SaveEntrypointRequest("myProject", "../escaped", "GET", "/x");
        badName.setProcedureName("myProc");
        assertFalse(RequestValidator.validate(badName).isValid());
        final var adminProject = new SaveEntrypointRequest("admin", "e", "GET", "/x");
        adminProject.setProcedureName("myProc");
        assertFalse(RequestValidator.validate(adminProject).isValid());
    }

    private static SaveMiddlewareRequest middleware(MiddlewarePhase phase, String procedureName,
            InlineProcedure procedure) {
        final var request = new SaveMiddlewareRequest("myProject", "auth", phase);
        request.setProcedureName(procedureName);
        request.setProcedure(procedure);
        return request;
    }

    @Test
    public void test_save_middleware_needs_a_name_a_phase_and_exactly_one_procedure_source() {
        assertTrue(RequestValidator.validate(middleware(MiddlewarePhase.INGRESS, "myProc", null)).isValid());
        assertTrue(RequestValidator
                .validate(middleware(MiddlewarePhase.EGRESS, null, new InlineProcedure("myProc", "x"))).isValid());
        final var noPhase = RequestValidator.validate(middleware(null, "myProc", null));
        final var neither = RequestValidator.validate(middleware(MiddlewarePhase.INGRESS, null, null));
        final var both = RequestValidator
                .validate(middleware(MiddlewarePhase.INGRESS, "p1p", new InlineProcedure("p2p", "x")));

        assertEquals(ErrorCode.INVALID_MIDDLEWARE, noPhase.getErrorCode());
        assertEquals(ErrorCode.INVALID_MIDDLEWARE, neither.getErrorCode());
        assertEquals(ErrorCode.INVALID_MIDDLEWARE, both.getErrorCode());
        assertFalse(RequestValidator
                .validate(middleware(MiddlewarePhase.INGRESS, null, new InlineProcedure("myProc", " "))).isValid());
        assertFalse(RequestValidator.validate(new SaveMiddlewareRequest("myProject", "../x", MiddlewarePhase.INGRESS))
                .isValid());
        assertFalse(RequestValidator.validate(new SaveMiddlewareRequest("bad name", "auth", MiddlewarePhase.INGRESS))
                .isValid());
    }

    @Test
    public void test_a_phase_off_the_wire_is_upper_case_only() {
        for (final var phase : List.of("ingress", "BEFORE", "Egress")) {
            final var result = RequestValidator.validate(parsedMiddleware(phase));

            assertEquals(ErrorCode.INVALID_MIDDLEWARE, result.getErrorCode(), phase);
            assertTrue(result.getErrorMessage().contains("'phase' must be INGRESS or EGRESS"),
                    result.getErrorMessage());
        }
        final var upper = parsedMiddleware("EGRESS");
        assertEquals(MiddlewarePhase.EGRESS, upper.getPhase());
        assertTrue(RequestValidator.validate(upper).isValid());
    }

    private static SaveMiddlewareRequest parsedMiddleware(String phase) {
        return (SaveMiddlewareRequest) RequestParser.parseRequest("{\"type\":\"SAVE_MIDDLEWARE\",\"projectName\":"
                + "\"myProject\",\"name\":\"auth\",\"phase\":\"" + phase + "\",\"procedureName\":\"myProc\"}");
    }

    @Test
    public void test_delete_and_list_middlewares_validate_names() {
        assertTrue(RequestValidator.validate(new DeleteMiddlewareRequest("myProject", "auth")).isValid());
        assertFalse(RequestValidator.validate(new DeleteMiddlewareRequest("myProject", "")).isValid());
        assertFalse(RequestValidator.validate(new DeleteMiddlewareRequest(null, "auth")).isValid());
        assertTrue(RequestValidator.validate(new ListMiddlewaresRequest("myProject")).isValid());
        assertFalse(RequestValidator.validate(new ListMiddlewaresRequest("bad name")).isValid());
    }

    @Test
    public void test_delete_and_list_entrypoints_validate_names() {
        assertTrue(RequestValidator.validate(new DeleteEntrypointRequest("myProject", "getOrder")).isValid());
        assertFalse(RequestValidator.validate(new DeleteEntrypointRequest("myProject", "")).isValid());
        assertFalse(RequestValidator.validate(new DeleteEntrypointRequest(null, "getOrder")).isValid());
        assertTrue(RequestValidator.validate(new ListEntrypointsRequest("myProject")).isValid());
        assertFalse(RequestValidator.validate(new ListEntrypointsRequest("bad name")).isValid());
    }
}
