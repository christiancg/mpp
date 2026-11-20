package org.ccg.mpp.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.simplejs.SimpleJs;
import org.ccg.mpp.simplejs.builtins.DbModule;
import org.ccg.mpp.simplejs.exceptions.TypeErrorException;
import org.ccg.mpp.simplejs.host.ResourceLimits;
import org.ccg.mpp.simplejs.host.ScriptResult;
import org.ccg.mpp.simplejs.host.SimpleHostBindings;
import org.ccg.mpp.simplejs.values.EJsonInterop;
import org.ccg.mpp.simplejs.values.JsArray;
import org.ccg.mpp.simplejs.values.JsNativeFunction;
import org.ccg.mpp.simplejs.values.JsNull;
import org.ccg.mpp.simplejs.values.JsNumber;
import org.ccg.mpp.simplejs.values.JsObject;
import org.ccg.mpp.simplejs.values.JsString;
import org.ccg.mpp.simplejs.values.JsUndefined;
import org.ccg.mpp.simplejs.values.JsValue;
import org.ccg.mpp.unit.simplejs.host.FakeProjectAccess;
import org.junit.jupiter.api.Test;

public class DbModuleTest {
    private static JsValue call(JsObject db, String method, JsValue... args) {
        final var fn = (JsNativeFunction) db.get(method);
        return fn.invoke(JsUndefined.getInstance(), List.of(args));
    }

    @Test
    public void test_find_by_id() {
        final var fake = new FakeProjectAccess();
        final var stored = new JsonObject();
        stored.add("_id", new JsonString("x1"));
        fake.nextFindResult = stored;
        final var db = DbModule.create(fake, null, null, null);
        final var result = call(db, "findById", new JsString("mydb"), new JsString("users"), new JsString("x1"));
        assertInstanceOf(JsObject.class, result);
        assertEquals("x1", ((JsString) ((JsObject) result).get("_id")).getValue());
        assertEquals("findById:mydb/users/x1", fake.calls.getFirst());
    }

    @Test
    public void test_find_by_id_missing() {
        final var fake = new FakeProjectAccess();
        final var db = DbModule.create(fake, null, null, null);
        final var result = call(db, "findById", new JsString("d"), new JsString("c"), new JsString("nope"));
        assertInstanceOf(JsNull.class, result);
    }

    @Test
    public void test_aggregate() {
        final var fake = new FakeProjectAccess();
        final var db = DbModule.create(fake, null, null, null);
        final var pipeline = new JsArray();
        pipeline.push(new JsObject());
        final var result = call(db, "aggregate", new JsString("d"), new JsString("c"), pipeline);
        assertInstanceOf(JsArray.class, result);
        assertEquals(1, ((JsArray) result).length());
        assertEquals("aggregate:d/c/1", fake.calls.getFirst());
    }

    @Test
    public void test_save() {
        final var fake = new FakeProjectAccess();
        final var db = DbModule.create(fake, null, null, null);
        final var document = new JsObject();
        document.set("_id", new JsString("s1"));
        final var result = call(db, "save", new JsString("d"), new JsString("c"), document);
        assertInstanceOf(JsObject.class, result);
        assertEquals("save:d/c", fake.calls.getFirst());
    }

    @Test
    public void test_delete() {
        final var fake = new FakeProjectAccess();
        final var db = DbModule.create(fake, null, null, null);
        final var result = call(db, "delete", new JsString("d"), new JsString("c"), new JsString("s1"));
        assertInstanceOf(JsUndefined.class, result);
        assertEquals("delete:d/c/s1", fake.calls.getFirst());
    }

    @Test
    public void test_update_where_returns_the_count() {
        final var fake = new FakeProjectAccess();
        fake.nextUpdatedCount = 4L;
        final var db = DbModule.create(fake, null, null, null);
        final var condition = new JsObject();
        condition.set("fieldOperatorType", new JsString("EQUALS"));
        condition.set("field", new JsString("status"));
        condition.set("value", new JsString("pending"));
        final var patch = new JsObject();
        patch.set("status", new JsString("expired"));
        patch.set("by", new JsString("sweeper"));
        final var result = call(db, "updateWhere", new JsString("d"), new JsString("c"), condition, patch);
        assertEquals(4.0, assertInstanceOf(JsNumber.class, result).getValue());
        assertEquals("updateWhere:d/c/status/2", fake.calls.getFirst());
    }

    @Test
    public void test_delete_where_returns_the_count() {
        final var fake = new FakeProjectAccess();
        fake.nextDeletedCount = 7L;
        final var db = DbModule.create(fake, null, null, null);
        final var condition = new JsObject();
        condition.set("fieldOperatorType", new JsString("EQUALS"));
        condition.set("field", new JsString("status"));
        condition.set("value", new JsString("archived"));
        final var result = call(db, "deleteWhere", new JsString("d"), new JsString("c"), condition);
        assertEquals(7.0, assertInstanceOf(JsNumber.class, result).getValue());
        assertEquals("deleteWhere:d/c/status", fake.calls.getFirst());
    }

    @Test
    public void test_list_operations() {
        final var fake = new FakeProjectAccess();
        final var db = DbModule.create(fake, null, null, null);
        final var collections = call(db, "listCollections", new JsString("d"));
        assertInstanceOf(JsArray.class, collections);
        assertEquals(2, ((JsArray) collections).length());
        final var projects = call(db, "listProjects");
        assertEquals(1, ((JsArray) projects).length());
        assertTrue(fake.calls.contains("listProjects"));
    }

    @Test
    public void test_interop_roundtrip() {
        final var object = new JsObject();
        object.set("_id", new JsString("r1"));
        final var restored = (JsObject) EJsonInterop.fromEjson(EJsonInterop.toEjson(object));
        assertEquals("r1", ((JsString) restored.get("_id")).getValue());
    }

    @Test
    public void test_bulk_save() {
        final var fake = new FakeProjectAccess();
        final var db = DbModule.create(fake, null, null, null);
        final var documents = new JsArray();
        documents.push(new JsObject());
        documents.push(new JsObject());
        final var result = (JsObject) call(db, "bulkSave", new JsString("d"), new JsString("c"), documents);
        assertEquals("bulkSave:d/c/2", fake.calls.getFirst());
        assertEquals(1, ((JsArray) result.get("inserted")).length());
        assertEquals(1, ((JsArray) result.get("updated")).length());
    }

    @Test
    public void test_bulk_save_requires_documents() {
        final var db = DbModule.create(new FakeProjectAccess(), null, null, null);
        assertThrows(TypeErrorException.class,
                () -> call(db, "bulkSave", new JsString("d"), new JsString("c"), new JsString("nope")));
    }

    @Test
    public void test_insert() {
        final var fake = new FakeProjectAccess();
        final var db = DbModule.create(fake, null, null, null);
        final var document = new JsObject();
        document.set("_id", new JsString("i1"));
        final var result = call(db, "insert", new JsString("d"), new JsString("c"), document);
        assertInstanceOf(JsObject.class, result);
        assertEquals("insert:d/c", fake.calls.getFirst());
    }

    @Test
    public void test_insert_rejects_a_non_object_document() {
        final var db = DbModule.create(new FakeProjectAccess(), null, null, null);
        final var thrown = assertThrows(TypeErrorException.class,
                () -> call(db, "insert", new JsString("d"), new JsString("c"), new JsNumber(5)));
        assertEquals("db.insert expects a document", thrown.getMessage());
    }

    @Test
    public void test_save_without_a_document_is_a_type_error() {
        final var db = DbModule.create(new FakeProjectAccess(), null, null, null);
        assertThrows(TypeErrorException.class, () -> call(db, "save", new JsString("d"), new JsString("c")));
    }

    @Test
    public void test_save_without_any_argument_is_a_type_error() {
        final var db = DbModule.create(new FakeProjectAccess(), null, null, null);
        assertThrows(TypeErrorException.class, () -> call(db, "save"));
    }

    @Test
    public void test_save_rejects_a_non_object_document() {
        final var db = DbModule.create(new FakeProjectAccess(), null, null, null);
        assertThrows(TypeErrorException.class,
                () -> call(db, "save", new JsString("d"), new JsString("c"), new JsNumber(5)));
    }

    @Test
    public void test_aggregate_without_a_pipeline_is_a_type_error() {
        final var db = DbModule.create(new FakeProjectAccess(), null, null, null);
        assertThrows(TypeErrorException.class, () -> call(db, "aggregate", new JsString("d"), new JsString("c")));
    }

    @Test
    public void test_aggregate_rejects_a_non_array_pipeline() {
        final var db = DbModule.create(new FakeProjectAccess(), null, null, null);
        assertThrows(TypeErrorException.class,
                () -> call(db, "aggregate", new JsString("d"), new JsString("c"), new JsObject()));
    }

    @Test
    public void test_bulk_save_without_documents_is_a_type_error() {
        final var db = DbModule.create(new FakeProjectAccess(), null, null, null);
        assertThrows(TypeErrorException.class, () -> call(db, "bulkSave", new JsString("d"), new JsString("c")));
    }

    @Test
    public void test_bulk_save_accepts_an_empty_array() {
        final var fake = new FakeProjectAccess();
        final var db = DbModule.create(fake, null, null, null);
        final var result = (JsObject) call(db, "bulkSave", new JsString("d"), new JsString("c"), new JsArray());
        assertEquals("bulkSave:d/c/0", fake.calls.getFirst());
        assertInstanceOf(JsArray.class, result.get("inserted"));
    }

    @Test
    public void test_module_exposes_new_members() {
        final var db = DbModule.create(new FakeProjectAccess(), null, null, null);
        assertInstanceOf(JsNativeFunction.class, db.get("bulkSave"));
        assertInstanceOf(JsNativeFunction.class, db.get("transaction"));
    }

    private static ScriptResult runScript(FakeProjectAccess fake, String source) {
        return new SimpleJs().run(source,
                new SimpleHostBindings(new JsonObject(), fake, null, ResourceLimits.unlimited()));
    }

    @Test
    public void test_transaction_commits() {
        final var fake = new FakeProjectAccess();
        final var result = runScript(fake, """
                import db from "db";
                return db.transaction(() => { db.delete("d", "c", "x"); return 7; });
                """);
        assertFalse(result.isError());
        assertEquals(7, result.getValue().asJsonNumber().asInteger());
        assertEquals(List.of("beginTransaction", "delete:d/c/x", "commitTransaction"), fake.calls);
    }

    @Test
    public void test_transaction_rolls_back_on_throw() {
        final var fake = new FakeProjectAccess();
        final var result = runScript(fake, """
                import db from "db";
                db.transaction(() => { throw new Error("boom"); });
                """);
        assertTrue(result.isError());
        assertEquals(List.of("beginTransaction", "rollbackTransaction"), fake.calls);
    }

    @Test
    public void test_transaction_rejects_suspendable_callbacks() {
        for (final var callback : List.of("42", "async function () {}", "function* () {}")) {
            final var fake = new FakeProjectAccess();
            final var result = runScript(fake, "import db from \"db\";\ndb.transaction(" + callback + ");");
            assertTrue(result.isError(), callback);
            assertEquals("TypeError", result.getErrorName(), callback);
            assertTrue(fake.calls.isEmpty(), callback);
        }
    }

    @Test
    public void test_transaction_rejects_a_returned_promise() {
        final var fake = new FakeProjectAccess();
        final var result = runScript(fake, """
                import db from "db";
                db.transaction(() => Promise.resolve(1));
                """);
        assertTrue(result.isError());
        assertEquals(List.of("beginTransaction", "rollbackTransaction"), fake.calls);
    }

    @Test
    public void test_an_error_thrown_by_the_callback_still_rolls_back() {
        final var fake = new FakeProjectAccess();
        final var ops = org.mockito.Mockito.mock(org.ccg.mpp.simplejs.builtins.InterpreterOps.class);
        org.mockito.Mockito.when(ops.call(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenThrow(new StackOverflowError("simulated"));
        final var db = DbModule.create(fake, ops, null, null);
        final var callback = new JsNativeFunction("cb", (_, _) -> JsUndefined.getInstance());

        assertThrows(StackOverflowError.class, () -> call(db, "transaction", callback));

        assertEquals(List.of("beginTransaction", "rollbackTransaction"), fake.calls,
                "an Error out of the callback must still release the transaction's write locks");
    }

    @Test
    public void test_nested_transaction_is_rejected() {
        final var fake = new FakeProjectAccess();
        fake.rejectNestedTransaction = true;
        final var result = runScript(fake, """
                import db from "db";
                db.transaction(() => db.transaction(() => 1));
                """);
        assertTrue(result.isError());
        assertEquals(List.of("beginTransaction", "beginTransaction", "rollbackTransaction"), fake.calls);
    }

    private static void assertRefused(String expectedMessage, String method, JsValue... args) {
        final var fake = new FakeProjectAccess();
        final var db = DbModule.create(fake, null, null, null);
        final var refusal = assertThrows(TypeErrorException.class, () -> call(db, method, args));
        assertTrue(refusal.getMessage().contains(expectedMessage), refusal.getMessage());
        assertTrue(fake.calls.isEmpty(), fake.calls.toString());
    }

    @Test
    public void test_delete_without_an_id_is_a_type_error_and_reaches_no_project_call() {
        assertRefused("db.delete expects a string id", "delete", new JsString("d"), new JsString("c"));
    }

    @Test
    public void test_delete_with_a_number_id_is_a_type_error() {
        assertRefused("db.delete expects a string id", "delete", new JsString("d"), new JsString("c"), new JsNumber(5));
    }

    @Test
    public void test_delete_with_a_null_id_is_a_type_error() {
        assertRefused("db.delete expects a string id", "delete", new JsString("d"), new JsString("c"),
                JsNull.getInstance());
    }

    @Test
    public void test_delete_where_without_a_condition_is_a_type_error_and_reaches_no_project_call() {
        assertRefused("db.deleteWhere expects a condition object", "deleteWhere", new JsString("d"), new JsString("c"));
    }

    @Test
    public void test_delete_where_with_an_array_condition_is_a_type_error() {
        assertRefused("db.deleteWhere expects a condition object", "deleteWhere", new JsString("d"), new JsString("c"),
                new JsArray());
    }

    @Test
    public void test_delete_where_with_a_number_collection_is_a_type_error() {
        assertRefused("db.deleteWhere expects a string collection name", "deleteWhere", new JsString("d"),
                new JsNumber(1), new JsObject());
    }

    @Test
    public void test_update_where_without_a_condition_is_a_type_error() {
        assertRefused("db.updateWhere expects a condition object", "updateWhere", new JsString("d"), new JsString("c"));
    }

    @Test
    public void test_update_where_without_a_patch_is_a_type_error() {
        assertRefused("db.updateWhere expects an object of fields to set", "updateWhere", new JsString("d"),
                new JsString("c"), new JsObject());
    }

    @Test
    public void test_update_where_with_an_array_patch_is_a_type_error() {
        assertRefused("db.updateWhere expects an object of fields to set", "updateWhere", new JsString("d"),
                new JsString("c"), new JsObject(), new JsArray());
    }

    @Test
    public void test_find_by_id_with_an_object_id_is_a_type_error() {
        assertRefused("db.findById expects a string id", "findById", new JsString("d"), new JsString("c"),
                new JsObject());
    }

    @Test
    public void test_find_by_id_with_a_non_string_project_is_a_type_error() {
        assertRefused("db.findById expects a string project name", "findById", new JsNumber(1), new JsString("c"),
                new JsString("x"));
    }

    @Test
    public void test_save_with_a_non_string_collection_is_refused_before_the_payload_is_converted() {
        assertRefused("db.save expects a string collection name", "save", new JsString("d"), new JsNumber(7),
                new JsNumber(5));
    }

    @Test
    public void test_aggregate_with_a_missing_collection_is_a_type_error() {
        assertRefused("db.aggregate expects a string collection name", "aggregate", new JsString("d"));
    }

    @Test
    public void test_bulk_save_with_a_non_string_project_is_a_type_error() {
        assertRefused("db.bulkSave expects a string project name", "bulkSave", JsUndefined.getInstance(),
                new JsString("c"), new JsArray());
    }

    @Test
    public void test_list_collections_without_a_project_is_a_type_error() {
        assertRefused("db.listCollections expects a string project name", "listCollections");
    }

    @Test
    public void test_cursor_with_a_non_string_project_is_a_type_error() {
        assertRefused("db.cursor expects a string project name", "cursor", new JsObject(), new JsString("c"),
                new JsArray());
    }

    @Test
    public void test_string_arguments_still_reach_the_project_unchanged() {
        final var fake = new FakeProjectAccess();
        final var db = DbModule.create(fake, null, null, null);
        call(db, "delete", new JsString("d"), new JsString("c"), new JsString("undefined"));
        call(db, "findById", new JsString("d"), new JsString("c"), new JsString("5"));
        call(db, "listCollections", new JsString("d"));
        assertEquals(List.of("delete:d/c/undefined", "findById:d/c/5", "listCollections:d"), fake.calls);
    }
}
