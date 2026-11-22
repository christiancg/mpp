package org.ccg.mpp.cluster.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Objects;
import org.ccg.mpp.data.EntrypointDefinition;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.admin.AdminRecordKey;
import org.ccg.mpp.ops.entrypoint.EntrypointRouter;
import org.ccg.mpp.ops.entrypoint.RouteMatch;
import org.ccg.mpp.ops.req.DeleteEntrypointRequest;
import org.ccg.mpp.ops.req.InlineProcedure;
import org.ccg.mpp.ops.req.SaveEntrypointRequest;
import org.ccg.mpp.test.TestGlobals;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class EntrypointRecordsTest extends AdminRecordTestBase {
    private final EntrypointRouter router = IocContainer.get(EntrypointRouter.class);

    @AfterEach
    void forgetRoutes() {
        router.forgetProject(TestGlobals.PROJECT);
    }

    private AdminRecord entrypoint(String path, long version) {
        final var body = new EntrypointDefinition("read", "GET", path, "proc", 0, true, "alice", null, 1, 0, 0, "alice")
                .toJsonObject();
        body.addProperty("writeVersion", Long.toString(version));
        return new AdminRecord(AdminRecordKey.entrypoint(TestGlobals.PROJECT, "read"), version, body);
    }

    @Test
    public void test_a_replicated_entrypoint_is_stored_cached_and_routed() throws Exception {
        AdminRecordMerge.apply(
                List.of(procedure(TestGlobals.PROJECT, "proc", clock.next()), entrypoint("/orders/{id}", clock.next())),
                true);

        assertNotNull(cache.getEntrypoint(TestGlobals.PROJECT, "read"));
        assertInstanceOf(RouteMatch.Found.class,
                router.table(TestGlobals.PROJECT).match("GET", List.of("orders", "o1")));
        final var key = AdminRecordKey.entrypoint(TestGlobals.PROJECT, "read");
        assertTrue(AdminRecords.all().stream().anyMatch(record -> record.key().equals(key)));
        assertEquals("/orders/{id}",
                Objects.requireNonNull(AdminRecords.live(key)).body().get("path").asJsonString().getValue());
    }

    @Test
    public void test_a_replicated_tombstone_removes_the_route() throws Exception {
        AdminRecordMerge.apply(List.of(entrypoint("/x", clock.next())), true);
        final var key = AdminRecordKey.entrypoint(TestGlobals.PROJECT, "read");

        AdminRecordMerge.apply(List.of(tombstone(key, clock.next())), true);

        assertNull(cache.getEntrypoint(TestGlobals.PROJECT, "read"));
        assertNull(fs.metadataFiles().readEntrypoint(TestGlobals.PROJECT, "read"));
        assertInstanceOf(RouteMatch.NoRoute.class, router.table(TestGlobals.PROJECT).match("GET", List.of("x")));
    }

    @Test
    public void test_an_entrypoint_record_name_is_validated_like_a_definition() {
        assertNotNull(AdminRecordNames.parse("entrypoint|" + TestGlobals.PROJECT + "|read"));
        assertNull(AdminRecordNames.parse("entrypoint|" + TestGlobals.PROJECT + "|../x"));
        assertNull(AdminRecordNames.parse("entrypoint|admin|read"));
    }

    @Test
    public void test_saving_names_the_records_it_touches() {
        final var byName = new SaveEntrypointRequest(TestGlobals.PROJECT, "read", "GET", "/x");
        byName.setProcedureName("proc");
        final var inline = new SaveEntrypointRequest(TestGlobals.PROJECT, "read", "GET", "/x");
        inline.setProcedure(new InlineProcedure("fresh", "return 1;"));

        assertEquals(List.of(AdminRecordKey.entrypoint(TestGlobals.PROJECT, "read")), AdminRecordKey.touchedBy(byName));
        assertEquals(List.of(AdminRecordKey.procedure(TestGlobals.PROJECT, "fresh"),
                AdminRecordKey.entrypoint(TestGlobals.PROJECT, "read")), AdminRecordKey.touchedBy(inline));
        assertEquals(List.of(AdminRecordKey.entrypoint(TestGlobals.PROJECT, "read")),
                AdminRecordKey.touchedBy(new DeleteEntrypointRequest(TestGlobals.PROJECT, "read")));
        assertEquals(AdminRecordKey.entrypoint(TestGlobals.PROJECT, "read"),
                AdminRecordKey.parse("entrypoint|" + TestGlobals.PROJECT + "|read"));
    }
}
