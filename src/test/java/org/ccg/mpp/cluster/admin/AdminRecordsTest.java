package org.ccg.mpp.cluster.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.admin.AdminRecordKey;
import org.ccg.mpp.ops.admin.AdminStamp;
import org.ccg.mpp.ops.admin.StoredDefinitions;
import org.ccg.mpp.ops.index.IndexOperationHelper;
import org.ccg.mpp.ops.req.ChangePermissionsRequest;
import org.ccg.mpp.ops.req.CreateCollectionRequest;
import org.ccg.mpp.ops.req.CreateIndexRequest;
import org.ccg.mpp.ops.req.CreateProjectRequest;
import org.ccg.mpp.ops.req.CreateUserRequest;
import org.ccg.mpp.ops.req.DeleteProcedureRequest;
import org.ccg.mpp.ops.req.DeleteScheduleRequest;
import org.ccg.mpp.ops.req.DeleteSchemaRequest;
import org.ccg.mpp.ops.req.DeleteUserRequest;
import org.ccg.mpp.ops.req.DropIndexRequest;
import org.ccg.mpp.ops.req.FindByIdRequest;
import org.ccg.mpp.ops.req.OperationRequest;
import org.ccg.mpp.ops.req.SaveProcedureRequest;
import org.ccg.mpp.ops.req.SaveScheduleRequest;
import org.ccg.mpp.ops.req.SaveTriggerRequest;
import org.ccg.mpp.ops.req.SetPasswordRequest;
import org.ccg.mpp.test.TestGlobals;
import org.junit.jupiter.api.Test;

public class AdminRecordsTest extends AdminRecordTestBase {

    private static List<String> ids(List<AdminRecord> records) {
        return records.stream().map(record -> record.key().id()).toList();
    }

    @Test
    public void test_all_lists_every_live_record_and_every_tombstone() throws Exception {
        AdminRecordMerge.apply(List.of(procedure(TestGlobals.PROJECT, "proc", clock.next()),
                schema(TestGlobals.COLL, clock.next()), user("bob", clock.next())), true);
        AdminStamp.tombstoneAt(AdminRecordKey.user("gone"), clock.next());

        final var ids = ids(AdminRecords.all());

        assertTrue(ids.contains(AdminRecordKey.project(TestGlobals.PROJECT).id()));
        assertTrue(ids.contains(AdminRecordKey.collection(TestGlobals.PROJECT, TestGlobals.COLL).id()));
        assertTrue(ids.contains(AdminRecordKey.schema(TestGlobals.PROJECT, TestGlobals.COLL).id()));
        assertTrue(ids.contains(AdminRecordKey.procedure(TestGlobals.PROJECT, "proc").id()));
        assertTrue(ids.contains(AdminRecordKey.user("bob").id()));
        assertTrue(ids.contains(AdminRecordKey.user("gone").id()));
        assertFalse(ids.contains(AdminRecordKey.triggers(TestGlobals.PROJECT, TestGlobals.COLL).id()));
        assertEquals(ids.size(), ids.stream().distinct().count());
    }

    @Test
    public void test_of_skips_keys_that_are_neither_live_nor_tombstoned() throws Exception {
        final var records = AdminRecords.of(List.of(AdminRecordKey.collection(TestGlobals.PROJECT, TestGlobals.COLL),
                AdminRecordKey.procedure(TestGlobals.PROJECT, "missing")));

        assertEquals(List.of(AdminRecordKey.collection(TestGlobals.PROJECT, TestGlobals.COLL).id()), ids(records));
    }

    @Test
    public void test_current_prefers_a_tombstone_at_or_above_the_live_version() {
        final var key = AdminRecordKey.collection(TestGlobals.PROJECT, TestGlobals.COLL);
        final var live = liveVersion(key);

        assertTrue(AdminRecords.current(key, Map.of(key.id(), live)).isTombstone());
        assertFalse(AdminRecords.current(key, Map.of(key.id(), live - 1)).isTombstone());
        assertFalse(AdminRecords.current(key, Map.of()).isTombstone());
        assertNull(AdminRecords.current(AdminRecordKey.user("nobody"), Map.of()));
    }

    @Test
    public void test_an_unreadable_definition_is_left_out() throws Exception {
        fs.metadataFiles().writeProcedure(TestGlobals.PROJECT, "broken", "{not json");

        assertNull(AdminRecords.live(AdminRecordKey.procedure(TestGlobals.PROJECT, "broken")));
        assertFalse(ids(AdminRecords.all()).contains(AdminRecordKey.procedure(TestGlobals.PROJECT, "broken").id()));
    }

    @Test
    public void test_creating_and_dropping_an_index_moves_the_collection_record_forward() {
        final var key = AdminRecordKey.collection(TestGlobals.PROJECT, TestGlobals.COLL);
        final var created = liveVersion(key);

        assertEquals(OperationStatus.OK,
                IndexOperationHelper
                        .processCreateIndex(new CreateIndexRequest(TestGlobals.PROJECT, TestGlobals.COLL, "email"))
                        .getStatus());
        final var indexed = liveVersion(key);
        assertTrue(indexed > created);

        assertEquals(OperationStatus.OK, IndexOperationHelper
                .processDropIndex(new DropIndexRequest(TestGlobals.PROJECT, TestGlobals.COLL, "email")).getStatus());
        assertTrue(liveVersion(key) > indexed);
    }

    private static void assertTouches(OperationRequest request, AdminRecordKey key) {
        assertEquals(List.of(key), AdminRecordKey.touchedBy(request));
    }

    @Test
    public void test_every_coordinated_op_names_the_record_it_touches() {
        final var db = TestGlobals.PROJECT;
        final var coll = TestGlobals.COLL;
        assertTouches(new CreateProjectRequest(db), AdminRecordKey.project(db));
        assertTouches(new CreateCollectionRequest(db, coll), AdminRecordKey.collection(db, coll));
        assertTouches(new DeleteSchemaRequest(db, coll), AdminRecordKey.schema(db, coll));
        assertTouches(new SaveTriggerRequest(db, coll, "t", List.of("CREATED"), "p"),
                AdminRecordKey.triggers(db, coll));
        assertTouches(new SaveProcedureRequest(db, "p", "return 1;"), AdminRecordKey.procedure(db, "p"));
        assertTouches(new DeleteProcedureRequest(db, "p"), AdminRecordKey.procedure(db, "p"));
        assertTouches(new SaveScheduleRequest(db, "s", "p"), AdminRecordKey.schedule(db, "s"));
        assertTouches(new DeleteScheduleRequest(db, "s"), AdminRecordKey.schedule(db, "s"));
        final var createUser = new CreateUserRequest();
        createUser.setUsername("u");
        assertTouches(createUser, AdminRecordKey.user("u"));
        final var deleteUser = new DeleteUserRequest();
        deleteUser.setUsername("u");
        assertTouches(deleteUser, AdminRecordKey.user("u"));
        final var setPassword = new SetPasswordRequest();
        setPassword.setUsername("u");
        assertTouches(setPassword, AdminRecordKey.user("u"));
        final var changePermissions = new ChangePermissionsRequest();
        changePermissions.setUsername("u");
        assertTouches(changePermissions, AdminRecordKey.user("u"));
        assertTrue(AdminRecordKey.touchedBy(new FindByIdRequest(db, coll)).isEmpty());
    }

    @Test
    public void test_legacy_definitions_are_stamped_once_and_keep_their_content() throws Exception {
        fs.metadataFiles().writeProcedure(TestGlobals.PROJECT, "legacy",
                "{\"name\":\"legacy\",\"source\":\"return 1;\"}");
        fs.metadataFiles().writeCollectionSchema(TestGlobals.PROJECT, TestGlobals.COLL, "{\"type\":\"object\"}");

        AdminRecords.stampUnversioned();

        final var procedure = Objects
                .requireNonNull(AdminRecords.live(AdminRecordKey.procedure(TestGlobals.PROJECT, "legacy")));
        assertTrue(procedure.version() > AdminStamp.BOOTSTRAP_VERSION);
        assertEquals("return 1;", procedure.body().get("source").asJsonString().getValue());
        final var schema = StoredDefinitions.schema(TestGlobals.PROJECT, TestGlobals.COLL);
        assertTrue(AdminStamp.versionOf(schema) > AdminStamp.BOOTSTRAP_VERSION);
        assertEquals("object", AdminStamp.unwrappedSchema(schema).get("type").asJsonString().getValue());
        final var stamped = procedure.version();

        AdminRecords.stampUnversioned();

        assertEquals(stamped, liveVersion(AdminRecordKey.procedure(TestGlobals.PROJECT, "legacy")));
    }

    @Test
    public void test_a_legacy_row_outranks_a_fresh_nodes_bootstrap_admin_after_stamping() {
        AdminRecordMerge.apply(List.of(user("legacyadmin", clock.next())), true);
        cache.getPkIndexAdminUserEntry("legacyadmin").setVersion(0L);
        cache.getPkIndexAdminProjectEntry(TestGlobals.PROJECT).setVersion(0L);

        AdminRecords.stampUnversioned();

        final var user = Objects.requireNonNull(AdminRecords.live(AdminRecordKey.user("legacyadmin")));
        assertTrue(user.outranks(user("legacyadmin", AdminStamp.BOOTSTRAP_VERSION)));
        assertEquals("hash-legacyadmin", cache.getAdminUserEntry("legacyadmin").getPasswordHash());
        assertTrue(liveVersion(AdminRecordKey.project(TestGlobals.PROJECT)) > 0);
    }
}
