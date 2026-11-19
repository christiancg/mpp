package org.ccg.mpp.unit.simplejs.host;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.HashSet;
import org.ccg.mpp.config.ConfigKey;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.ops.UserOperationHelper;
import org.ccg.mpp.ops.req.CreateUserRequest;
import org.ccg.mpp.simplejs.SimpleJs;
import org.ccg.mpp.simplejs.exceptions.JsThrowException;
import org.ccg.mpp.simplejs.host.EnforcingProjectAccess;
import org.ccg.mpp.simplejs.host.ResourceLimits;
import org.ccg.mpp.simplejs.host.SimpleHostBindings;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class EnforcingProjectAccessDeleteWhereTest {
    private static final String ADMIN = "deletewhereadmin";
    private static final String NOBODY = "deletewherenobody";

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
        createUser(ADMIN, true);
        createUser(NOBODY, false);
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static void createUser(String username, boolean admin) {
        final var request = new CreateUserRequest();
        request.setUsername(username);
        request.setPassword("password123");
        request.setAdmin(admin);
        request.setGlobalPermissions(new HashSet<>());
        request.setProjectPermissions(new HashMap<>());
        request.setCollectionPermissions(new HashMap<>());
        UserOperationHelper.processCreateUser(request);
    }

    private static JsonObject doc(String id) {
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        return object;
    }

    private static JsonObject valueIs(String value) {
        final var condition = new JsonObject();
        condition.add("fieldOperatorType", new JsonString("EQUALS"));
        condition.add("field", new JsonString("value"));
        condition.add("value", new JsonString(value));
        return condition;
    }

    private static JsonObject docWithValue(String id, String value) {
        final var object = doc(id);
        object.add("value", new JsonString(value));
        return object;
    }

    @Test
    public void test_delete_where() {
        final var db = new EnforcingProjectAccess(ADMIN, null);
        db.save(TestGlobals.PROJECT, TestGlobals.COLL, docWithValue("dw1", "purge-me"));
        db.save(TestGlobals.PROJECT, TestGlobals.COLL, docWithValue("dw2", "purge-me"));
        db.save(TestGlobals.PROJECT, TestGlobals.COLL, docWithValue("dw3", "keep-me"));

        assertEquals(2L, db.deleteWhere(TestGlobals.PROJECT, TestGlobals.COLL, valueIs("purge-me")));

        assertNull(db.findById(TestGlobals.PROJECT, TestGlobals.COLL, "dw1"));
        assertNull(db.findById(TestGlobals.PROJECT, TestGlobals.COLL, "dw2"));
        assertNotNull(db.findById(TestGlobals.PROJECT, TestGlobals.COLL, "dw3"));
    }

    @Test
    public void test_delete_where_of_nothing_returns_zero() {
        final var db = new EnforcingProjectAccess(ADMIN, null);
        assertEquals(0L, db.deleteWhere(TestGlobals.PROJECT, TestGlobals.COLL, valueIs("matches-nothing")));
    }

    @Test
    public void test_delete_where_past_the_cap_throws() throws Exception {
        final var configuration = Configuration.getInstance();
        final var db = new EnforcingProjectAccess(ADMIN, null);
        db.save(TestGlobals.PROJECT, TestGlobals.COLL, docWithValue("cap1", "capped"));
        db.save(TestGlobals.PROJECT, TestGlobals.COLL, docWithValue("cap2", "capped"));
        TestUtils.setPrivateField(configuration, "deleteMaxMatches", 1);
        try {
            assertThrows(JsThrowException.class,
                    () -> db.deleteWhere(TestGlobals.PROJECT, TestGlobals.COLL, valueIs("capped")));
        } finally {
            TestUtils.setPrivateField(configuration, "deleteMaxMatches", ConfigKey.DELETE_MAX_MATCHES.defaultValue());
        }
        assertNotNull(db.findById(TestGlobals.PROJECT, TestGlobals.COLL, "cap1"));
    }

    @Test
    public void test_delete_where_with_an_invalid_condition_throws() {
        final var db = new EnforcingProjectAccess(ADMIN, null);
        final var condition = new JsonObject();
        condition.add("conjunctionType", new JsonString("NEVER"));
        assertThrows(JsThrowException.class, () -> db.deleteWhere(TestGlobals.PROJECT, TestGlobals.COLL, condition));
    }

    @Test
    public void test_failed_delete_where_throws() {
        final var db = new EnforcingProjectAccess(NOBODY, null);
        assertThrows(JsThrowException.class,
                () -> db.deleteWhere(TestGlobals.PROJECT, TestGlobals.COLL, valueIs("purge-me")));
    }

    @Test
    public void test_a_script_deletes_where_and_reads_the_count() {
        final var db = new EnforcingProjectAccess(ADMIN, null);
        db.save(TestGlobals.PROJECT, TestGlobals.COLL, docWithValue("js1", "from-script"));
        final var engine = new SimpleJs();
        final var source = "import db from 'db'; return db.deleteWhere('" + TestGlobals.PROJECT + "', '"
                + TestGlobals.COLL + "', {fieldOperatorType: 'EQUALS', field: 'value', value: 'from-script'});";

        final var result = engine.run(source,
                new SimpleHostBindings(new JsonObject(), db, null, ResourceLimits.unlimited()));

        assertFalse(result.isError());
        assertEquals(1.0, result.getValue().asJsonNumber().getValue().doubleValue());
    }

}
