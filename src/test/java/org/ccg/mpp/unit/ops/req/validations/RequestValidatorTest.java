package org.ccg.mpp.unit.ops.req.validations;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.ejson.elements.JsonNumber;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.ops.req.AggregateRequest;
import org.ccg.mpp.ops.req.BulkSaveRequest;
import org.ccg.mpp.ops.req.CloseConnectionRequest;
import org.ccg.mpp.ops.req.CreateIndexRequest;
import org.ccg.mpp.ops.req.CreateProjectRequest;
import org.ccg.mpp.ops.req.DeleteRequest;
import org.ccg.mpp.ops.req.DropIndexRequest;
import org.ccg.mpp.ops.req.FindByIdRequest;
import org.ccg.mpp.ops.req.ReindexRequest;
import org.ccg.mpp.ops.req.SaveRequest;
import org.ccg.mpp.ops.req.agg.FieldOperatorType;
import org.ccg.mpp.ops.req.agg.operators.FieldOperator;
import org.ccg.mpp.ops.req.agg.step.CountAggregationStep;
import org.ccg.mpp.ops.req.agg.step.FilterAggregationStep;
import org.ccg.mpp.ops.req.agg.step.LimitAggregationStep;
import org.ccg.mpp.ops.req.validations.RequestValidator;
import org.junit.jupiter.api.Test;

public class RequestValidatorTest {
    @Test
    public void validate_closeConnection_returnsOk() {
        assertTrue(RequestValidator.validate(new CloseConnectionRequest()).isValid());
    }

    @Test
    public void validate_resolveTransaction_valid() {
        final var request = new org.ccg.mpp.ops.req.ResolveTransactionRequest();
        request.setDtxId("11111111-1111-1111-1111-111111111111");
        request.setDecision(org.ccg.mpp.ops.req.ResolveTransactionRequest.DECISION_COMMIT);
        assertTrue(RequestValidator.validate(request).isValid());
    }

    @Test
    public void validate_resolveTransaction_blankDtxId_fails() {
        final var request = new org.ccg.mpp.ops.req.ResolveTransactionRequest();
        request.setDecision(org.ccg.mpp.ops.req.ResolveTransactionRequest.DECISION_ABORT);
        assertFalse(RequestValidator.validate(request).isValid());
    }

    @Test
    public void validate_resolveTransaction_badDecision_fails() {
        final var request = new org.ccg.mpp.ops.req.ResolveTransactionRequest();
        request.setDtxId("11111111-1111-1111-1111-111111111111");
        request.setDecision("maybe");
        assertFalse(RequestValidator.validate(request).isValid());
    }

    @Test
    public void validate_listTransactions_returnsOk() {
        assertTrue(RequestValidator.validate(new org.ccg.mpp.ops.req.ListTransactionsRequest()).isValid());
    }

    @Test
    public void validate_save_validObjectNoId_returnsOk() {
        final var req = new SaveRequest("myProject", "myColl");
        req.setObject(new JsonObject());
        assertTrue(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_save_validObjectWithValidId_returnsOk() {
        final var req = new SaveRequest("myProject", "myColl");
        req.setObject(new JsonObject());
        req.set_id("valid-id_123");
        assertTrue(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_save_nullObject_returnsFail() {
        final var req = new SaveRequest("myProject", "myColl");
        req.setObject(null);
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_save_idWithInvalidChars_returnsFail() {
        final var req = new SaveRequest("myProject", "myColl");
        req.setObject(new JsonObject());
        req.set_id("id with space");
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_save_idTooLong_returnsFail() {
        final var req = new SaveRequest("myProject", "myColl");
        req.setObject(new JsonObject());
        req.set_id("a".repeat(65));
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_save_idExactlyOneChar_returnsOk() {
        final var req = new SaveRequest("myProject", "myColl");
        req.setObject(new JsonObject());
        req.set_id("a");
        assertTrue(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_save_adminProject_returnsFail() {
        final var req = new SaveRequest("admin", "myColl");
        req.setObject(new JsonObject());
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_save_objectIdWithInvalidChars_returnsFail() {
        final var req = new SaveRequest("myProject", "myColl");
        final var obj = new JsonObject();
        obj.add("_id", new JsonString("has spaces!"));
        req.setObject(obj);
        assertFalse(RequestValidator.validate(req).isValid(),
                "an id only FIND_BY_ID and DELETE would refuse leaves the document unreachable forever");
    }

    @Test
    public void validate_save_objectIdValid_returnsOk() {
        final var req = new SaveRequest("myProject", "myColl");
        final var obj = new JsonObject();
        obj.add("_id", new JsonString("valid-id_123"));
        req.setObject(obj);
        assertTrue(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_save_nonStringObjectId_returnsFail() {
        final var req = new SaveRequest("myProject", "myColl");
        final var obj = new JsonObject();
        obj.add("_id", new JsonNumber(123));
        req.setObject(obj);
        assertFalse(RequestValidator.validate(req).isValid(), "a non-string _id must not reach the hard cast");
    }

    @Test
    public void validate_bulkSave_nonStringObjectId_returnsFail() {
        final var req = new BulkSaveRequest("myProject", "myColl");
        final var obj = new JsonObject();
        obj.add("_id", new JsonNumber(123));
        req.setObjects(List.of(obj));
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_bulkSave_validObjects_returnsOk() {
        final var req = new BulkSaveRequest("myProject", "myColl");
        req.setObjects(List.of(new JsonObject()));
        assertTrue(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_bulkSave_nullObjects_returnsFail() {
        final var req = new BulkSaveRequest("myProject", "myColl");
        req.setObjects(null);
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_bulkSave_emptyObjects_returnsFail() {
        final var req = new BulkSaveRequest("myProject", "myColl");
        req.setObjects(List.of());
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_bulkSave_objectWithInvalidId_returnsFail() {
        final var req = new BulkSaveRequest("myProject", "myColl");
        final var obj = new JsonObject();
        obj.add("_id", new JsonString("id with space"));
        req.setObjects(List.of(obj));
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_bulkSave_objectWithValidId_returnsOk() {
        final var req = new BulkSaveRequest("myProject", "myColl");
        final var obj = new JsonObject();
        obj.add("_id", new JsonString("valid-id"));
        req.setObjects(List.of(obj));
        assertTrue(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_findById_validId_returnsOk() {
        final var req = new FindByIdRequest("myProject", "myColl");
        req.set_id("abc123");
        assertTrue(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_findById_nullId_returnsFail() {
        final var req = new FindByIdRequest("myProject", "myColl");
        req.set_id(null);
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_findById_invalidIdChars_returnsFail() {
        final var req = new FindByIdRequest("myProject", "myColl");
        req.set_id("bad id!");
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_findById_adminProjectAllowed_returnsOk() {
        final var req = new FindByIdRequest("admin", "myColl");
        req.set_id("abc");
        assertTrue(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_delete_validId_returnsOk() {
        final var req = new DeleteRequest("myProject", "myColl");
        req.set_id("abc123");
        assertTrue(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_delete_nullId_returnsFail() {
        final var req = new DeleteRequest("myProject", "myColl");
        req.set_id(null);
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_delete_adminProject_returnsFail() {
        final var req = new DeleteRequest("admin", "myColl");
        req.set_id("abc");
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_aggregate_nullSteps_returnsFail() {
        final var req = new AggregateRequest("myProject", "myColl");
        req.setAggregationSteps(null);
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_aggregate_emptySteps_returnsOk() {
        final var req = new AggregateRequest("myProject", "myColl");
        req.setAggregationSteps(List.of());
        assertTrue(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_aggregate_validSteps_returnsOk() {
        final var req = new AggregateRequest("myProject", "myColl");
        final var filter = new FilterAggregationStep(
                new FieldOperator(FieldOperatorType.EQUALS, "name", new JsonString("test")));
        req.setAggregationSteps(List.of(filter));
        assertTrue(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_aggregate_invalidStep_returnsFail() {
        final var req = new AggregateRequest("myProject", "myColl");
        req.setAggregationSteps(List.of(new LimitAggregationStep(-1)));
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_aggregate_countAsLastStep_returnsOk() {
        final var req = new AggregateRequest("myProject", "myColl");
        final var filter = new FilterAggregationStep(
                new FieldOperator(FieldOperatorType.EQUALS, "name", new JsonString("test")));
        req.setAggregationSteps(List.of(filter, new CountAggregationStep()));
        assertTrue(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_aggregate_countOnlyStep_returnsOk() {
        final var req = new AggregateRequest("myProject", "myColl");
        req.setAggregationSteps(List.of(new CountAggregationStep()));
        assertTrue(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_aggregate_countNotLast_returnsFail() {
        final var req = new AggregateRequest("myProject", "myColl");
        final var filter = new FilterAggregationStep(
                new FieldOperator(FieldOperatorType.EQUALS, "name", new JsonString("test")));
        req.setAggregationSteps(List.of(filter, new CountAggregationStep(), filter));
        final var result = RequestValidator.validate(req);
        assertFalse(result.isValid());
        assertEquals("COUNT must be the last aggregation step", result.getErrorMessage());
    }

    @Test
    public void validate_createIndex_rejectsThePkField() {
        final var request = new org.ccg.mpp.ops.req.CreateIndexRequest("testProject", "testColl", "_id");
        assertFalse(RequestValidator.validate(request).isValid(),
                "the pk index is named like a field index on _id, so an accepted request deletes it");
    }

    @Test
    public void validate_dropIndex_rejectsThePkField() {
        final var request = new org.ccg.mpp.ops.req.DropIndexRequest("testProject", "testColl", "_id");
        assertFalse(RequestValidator.validate(request).isValid());
    }

    @Test
    public void validate_dropIndex_rejectsTheTombstoneName() {
        final var request = new org.ccg.mpp.ops.req.DropIndexRequest("testProject", "testColl", "tombstones");
        assertFalse(RequestValidator.validate(request).isValid());
    }

    @Test
    public void validate_dropIndex_acceptsAnOrdinaryField() {
        final var request = new org.ccg.mpp.ops.req.DropIndexRequest("testProject", "testColl", "score");
        assertTrue(RequestValidator.validate(request).isValid());
    }

    private static boolean createIndexValid(String fieldName) {
        return RequestValidator.validate(new CreateIndexRequest("myProject", "myCollection", fieldName)).isValid();
    }

    @Test
    public void test_index_field_name_charset_is_enforced() {
        assertFalse(createIndexValid("a b"));
        assertFalse(createIndexValid("a/b"));
        assertFalse(createIndexValid("a|b"));
        assertFalse(createIndexValid("a\ud800b"));
        assertFalse(createIndexValid("café"));
        assertFalse(createIndexValid("x".repeat(65)));
    }

    @Test
    public void test_nested_path_field_name_is_accepted() {
        assertTrue(createIndexValid("address.city"));
    }

    @Test
    public void test_hyphenated_field_name_is_accepted() {
        assertTrue(createIndexValid("first-name"));
        assertTrue(createIndexValid("plain"));
        assertTrue(createIndexValid("a_b"));
    }

    @Test
    public void test_index_field_charset_applies_to_drop_and_reindex() {
        assertFalse(RequestValidator.validate(new DropIndexRequest("myProject", "myCollection", "a b")).isValid());
        assertFalse(
                RequestValidator.validate(new ReindexRequest("myProject", "myCollection", List.of("a b"))).isValid());
        assertTrue(RequestValidator.validate(new ReindexRequest("myProject", "myCollection", List.of("address.city")))
                .isValid());
    }

    @Test
    public void test_admin_project_name_is_reserved_case_insensitively() {
        assertFalse(RequestValidator.validate(new CreateProjectRequest("Admin")).isValid());
        assertFalse(RequestValidator.validate(new CreateProjectRequest("ADMIN")).isValid());
        assertFalse(RequestValidator.validate(new CreateProjectRequest("admin")).isValid());
    }

    @Test
    public void test_admin_pages_name_is_reserved_case_insensitively() {
        assertFalse(RequestValidator.validate(new CreateProjectRequest("Admin_Pages")).isValid());
        assertFalse(RequestValidator.validate(new CreateProjectRequest("admin_pages")).isValid());
    }

    @Test
    public void test_script_runs_collection_is_reserved_case_insensitively() {
        final var request = new SaveRequest("myProject", "Script_Runs");
        final var object = new JsonObject();
        object.add("_id", new JsonString("a1"));
        request.setObject(object);
        request.set_id("a1");
        assertFalse(RequestValidator.validate(request).isValid());
    }

    @Test
    public void test_ordinary_names_are_still_accepted() {
        assertTrue(RequestValidator.validate(new CreateProjectRequest("myProject")).isValid());
        assertTrue(RequestValidator.validate(new CreateProjectRequest("administrators")).isValid());
    }
    private static JsonObject nestedDocument(int depth) {
        var inner = new JsonObject();
        for (var level = 1; level < depth; level++) {
            final var wrapper = new JsonObject();
            wrapper.add("a", inner);
            inner = wrapper;
        }
        return inner;
    }

    @Test
    public void validate_save_documentPastTheNestingLimit_returnsFail() {
        final var req = new SaveRequest("myProject", "myColl");
        req.setObject(nestedDocument(Globals.MAX_REQUEST_NESTING_DEPTH + 1));
        final var result = RequestValidator.validate(req);
        assertFalse(result.isValid());
        assertTrue(result.getErrorMessage().contains("nest"));
    }

    @Test
    public void validate_save_documentAtTheNestingLimit_returnsOk() {
        final var req = new SaveRequest("myProject", "myColl");
        req.setObject(nestedDocument(Globals.MAX_REQUEST_NESTING_DEPTH));
        assertTrue(RequestValidator.validate(req).isValid());
    }

    @Test
    public void validate_bulkSave_documentPastTheNestingLimit_returnsFail() {
        final var req = new BulkSaveRequest("myProject", "myColl");
        req.setObjects(List.of(new JsonObject(), nestedDocument(Globals.MAX_REQUEST_NESTING_DEPTH + 1)));
        assertFalse(RequestValidator.validate(req).isValid());
    }
}
