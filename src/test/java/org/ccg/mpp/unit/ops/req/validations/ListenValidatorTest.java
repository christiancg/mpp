package org.ccg.mpp.unit.ops.req.validations;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.ops.req.ListenRequest;
import org.ccg.mpp.ops.req.StopListenRequest;
import org.ccg.mpp.ops.req.agg.FieldOperatorType;
import org.ccg.mpp.ops.req.agg.operators.FieldOperator;
import org.ccg.mpp.ops.req.agg.step.FilterAggregationStep;
import org.ccg.mpp.ops.req.validations.RequestValidator;
import org.junit.jupiter.api.Test;

public class ListenValidatorTest {

    @Test
    public void validate_listen_validRequest_returnsOk() {
        final var req = new ListenRequest("myDb", "myColl");
        req.setAggregationSteps(List.of());

        assertTrue(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_listen_nullDb_returnsFail() {
        final var req = new ListenRequest(null, "coll");
        req.setAggregationSteps(List.of());

        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_listen_nullColl_returnsFail() {
        final var req = new ListenRequest("db", null);
        req.setAggregationSteps(List.of());

        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_listen_nullSteps_returnsFail() {
        final var req = new ListenRequest("myDb", "myColl");

        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_listen_withFilterStep_returnsOk() {
        final var req = new ListenRequest("myDb", "myColl");
        final var op = new FieldOperator(FieldOperatorType.EQUALS, "status", new JsonString("active"));
        req.setAggregationSteps(List.of(new FilterAggregationStep(op)));

        assertTrue(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_listen_withValuelessFilterStep_returnsFail() {
        final var req = new ListenRequest("myDb", "myColl");
        final var op = new FieldOperator(FieldOperatorType.EQUALS, "status", null);
        req.setAggregationSteps(List.of(new FilterAggregationStep(op)));

        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_stopListen_validUUID_returnsOk() {
        final var req = new StopListenRequest();
        req.setListenId(UUID.randomUUID().toString());

        assertTrue(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_stopListen_nullId_returnsFail() {
        final var req = new StopListenRequest();

        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_stopListen_blankId_returnsFail() {
        final var req = new StopListenRequest();
        req.setListenId("   ");

        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_stopListen_nonUuidId_returnsFail() {
        final var req = new StopListenRequest();
        req.setListenId("not-a-uuid");

        assertFalse(RequestValidator.validate(req).isValid());
    }
}
