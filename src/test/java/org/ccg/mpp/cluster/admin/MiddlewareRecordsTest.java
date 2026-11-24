package org.ccg.mpp.cluster.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Objects;
import org.ccg.mpp.data.EntrypointDefinition;
import org.ccg.mpp.data.MiddlewareDefinition;
import org.ccg.mpp.data.MiddlewareMatch;
import org.ccg.mpp.data.MiddlewarePhase;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.admin.AdminRecordKey;
import org.ccg.mpp.ops.middleware.MiddlewareChains;
import org.ccg.mpp.ops.req.DeleteMiddlewareRequest;
import org.ccg.mpp.ops.req.InlineProcedure;
import org.ccg.mpp.ops.req.SaveMiddlewareRequest;
import org.ccg.mpp.test.TestGlobals;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class MiddlewareRecordsTest extends AdminRecordTestBase {
    private static final EntrypointDefinition GET_ORDER = new EntrypointDefinition("getOrder", "GET", "/orders/{id}",
            "p", 0L, true, "alice", null, 1L, 0L, 0L, "alice");
    private final MiddlewareChains chains = IocContainer.get(MiddlewareChains.class);

    @AfterEach
    void forgetChains() {
        chains.forgetProject(TestGlobals.PROJECT);
    }

    private AdminRecord middleware(MiddlewarePhase phase, long version) {
        final var body = new MiddlewareDefinition("auth", phase, 1, MiddlewareMatch.EVERYTHING, "proc", 0L, true,
                "alice", null).stamped(1L, 0L, 0L, "alice").toJsonObject();
        body.addProperty("writeVersion", Long.toString(version));
        return new AdminRecord(AdminRecordKey.middleware(TestGlobals.PROJECT, "auth"), version, body);
    }

    @Test
    public void test_a_replicated_middleware_is_stored_cached_and_chained() throws Exception {
        AdminRecordMerge.apply(List.of(procedure(TestGlobals.PROJECT, "proc", clock.next()),
                middleware(MiddlewarePhase.INGRESS, clock.next())), true);

        assertNotNull(cache.getMiddleware(TestGlobals.PROJECT, "auth"));
        assertEquals(1, chains.chainFor(TestGlobals.PROJECT, GET_ORDER).ingress().size());
        final var key = AdminRecordKey.middleware(TestGlobals.PROJECT, "auth");
        assertTrue(AdminRecords.all().stream().anyMatch(record -> record.key().equals(key)));
        assertEquals("INGRESS",
                Objects.requireNonNull(AdminRecords.live(key)).body().get("phase").asJsonString().getValue());
    }

    @Test
    public void test_a_newer_record_replaces_the_phase() {
        AdminRecordMerge.apply(List.of(middleware(MiddlewarePhase.INGRESS, clock.next())), true);
        AdminRecordMerge.apply(List.of(middleware(MiddlewarePhase.EGRESS, clock.next())), true);

        assertEquals(MiddlewarePhase.EGRESS, cache.getMiddleware(TestGlobals.PROJECT, "auth").getPhase());
        assertEquals(1, chains.chainFor(TestGlobals.PROJECT, GET_ORDER).egress().size());
    }

    @Test
    public void test_a_replicated_tombstone_removes_the_middleware() throws Exception {
        AdminRecordMerge.apply(List.of(middleware(MiddlewarePhase.INGRESS, clock.next())), true);
        final var key = AdminRecordKey.middleware(TestGlobals.PROJECT, "auth");

        AdminRecordMerge.apply(List.of(tombstone(key, clock.next())), true);

        assertNull(cache.getMiddleware(TestGlobals.PROJECT, "auth"));
        assertNull(fs.metadataFiles().readMiddleware(TestGlobals.PROJECT, "auth"));
        assertTrue(chains.chainFor(TestGlobals.PROJECT, GET_ORDER).ingress().isEmpty());
    }

    @Test
    public void test_a_middleware_record_name_is_validated_like_a_definition() {
        assertNotNull(AdminRecordNames.parse("middleware|" + TestGlobals.PROJECT + "|auth"));
        assertNull(AdminRecordNames.parse("middleware|" + TestGlobals.PROJECT + "|../x"));
        assertNull(AdminRecordNames.parse("middleware|admin|auth"));
    }

    @Test
    public void test_saving_names_the_records_it_touches() {
        final var byName = new SaveMiddlewareRequest(TestGlobals.PROJECT, "auth", MiddlewarePhase.INGRESS);
        byName.setProcedureName("proc");
        final var inline = new SaveMiddlewareRequest(TestGlobals.PROJECT, "auth", MiddlewarePhase.INGRESS);
        inline.setProcedure(new InlineProcedure("fresh", "return 1;"));
        final var key = AdminRecordKey.middleware(TestGlobals.PROJECT, "auth");

        assertEquals(List.of(key), AdminRecordKey.touchedBy(byName));
        assertEquals(List.of(AdminRecordKey.procedure(TestGlobals.PROJECT, "fresh"), key),
                AdminRecordKey.touchedBy(inline));
        assertEquals(List.of(key), AdminRecordKey.touchedBy(new DeleteMiddlewareRequest(TestGlobals.PROJECT, "auth")));
        assertEquals(key, AdminRecordKey.parse("middleware|" + TestGlobals.PROJECT + "|auth"));
    }
}
