package org.ccg.mpp.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import org.ccg.mpp.ejson.elements.JsonArray;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.simplejs.SimpleJs;
import org.ccg.mpp.simplejs.host.BulkSaveOutcome;
import org.ccg.mpp.simplejs.host.ResourceLimits;
import org.ccg.mpp.simplejs.host.ScriptResult;
import org.ccg.mpp.simplejs.host.SimpleHostBindings;
import org.ccg.mpp.unit.simplejs.host.FakeProjectAccess;
import org.junit.jupiter.api.Test;

public class DbModuleChargingTest {
    private static final String PADDING = "x".repeat(512);

    private static JsonObject document(String id) {
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        object.add("payload", new JsonString(PADDING));
        return object;
    }

    private static final class CountingList extends AbstractList<JsonObject> {
        private final List<JsonObject> backing;
        private int read;

        private CountingList(List<JsonObject> backing) {
            this.backing = backing;
        }

        @Override
        public JsonObject get(int index) {
            read++;
            return backing.get(index);
        }

        @Override
        public int size() {
            return backing.size();
        }
    }

    private static final class BigResultProject extends FakeProjectAccess {
        private final CountingList results;

        private BigResultProject(int documents) {
            final var backing = new ArrayList<JsonObject>();
            for (var i = 0; i < documents; i++) {
                backing.add(document("d" + i));
            }
            this.results = new CountingList(backing);
        }

        @Override
        public List<JsonObject> aggregate(String db, String coll, JsonArray pipeline) {
            calls.add("aggregate:" + db + "/" + coll + "/" + pipeline.size());
            return results;
        }

        @Override
        public JsonObject findById(String db, String coll, String id) {
            calls.add("findById:" + db + "/" + coll + "/" + id);
            return document(id);
        }

        @Override
        public JsonObject save(String db, String coll, JsonObject document) {
            calls.add("save:" + db + "/" + coll);
            return document;
        }

        @Override
        public BulkSaveOutcome bulkSave(String db, String coll, List<JsonObject> documents) {
            calls.add("bulkSave:" + db + "/" + coll + "/" + documents.size());
            final var inserted = new ArrayList<String>();
            for (var i = 0; i < 5000; i++) {
                inserted.add("i" + i);
            }
            return new BulkSaveOutcome(inserted, List.of("u1"));
        }
    }

    private static ResourceLimits budget(long memory) {
        return new ResourceLimits(-1, -1, -1, true, false, List.of(), -1, -1, false, false,
                ResourceLimits.DEFAULT_MAX_MODULE_DEPTH, ResourceLimits.DEFAULT_MAX_LOG_LINES,
                ResourceLimits.DEFAULT_MAX_LOG_LINE_CHARS, memory, -1, -1, -1, 8,
                ResourceLimits.UNLIMITED_ARRAY_LENGTH);
    }

    private static ScriptResult run(FakeProjectAccess project, String source, long memory) {
        return new SimpleJs().run(source, new SimpleHostBindings(new JsonObject(), project, null, budget(memory)));
    }

    @Test
    public void test_aggregate_charges_each_document() {
        final var project = new BigResultProject(500);
        final var result = run(project, "import db from \"db\"; return db.aggregate('d', 'c', []).length;", 8192);
        assertTrue(result.isError());
        assertEquals("ScriptMemoryError", result.getErrorName());
    }

    @Test
    public void test_aggregate_aborts_partway_through_conversion() {
        final var project = new BigResultProject(500);
        run(project, "import db from \"db\"; db.aggregate('d', 'c', []);", 8192);
        assertTrue(project.results.read > 0, "some documents must have been converted");
        assertTrue(project.results.read < 500, "the whole list must not have been converted: " + project.results.read);
    }

    @Test
    public void test_find_by_id_charges_the_document() {
        final var project = new BigResultProject(1);
        final var result = run(project, "import db from \"db\"; return db.findById('d', 'c', 'x1');", 128);
        assertTrue(result.isError());
        assertEquals("ScriptMemoryError", result.getErrorName());
    }

    @Test
    public void test_bulk_save_charges_the_outcome_arrays() {
        final var project = new BigResultProject(1);
        final var result = run(project, "import db from \"db\"; return db.bulkSave('d', 'c', [{}]);", 16384);
        assertTrue(result.isError());
        assertEquals("ScriptMemoryError", result.getErrorName());
    }

    @Test
    public void test_save_argument_is_not_charged_twice() {
        final var project = new BigResultProject(1);
        final var result = run(project, """
                import db from "db";
                const document = { _id: "s1", payload: "y".repeat(1000) };
                return db.save('d', 'c', document)._id;
                """, 6000);
        assertFalse(result.isError(), result.getErrorMessage());
        assertEquals("s1", result.getValue().asJsonString().getValue());
    }
}
