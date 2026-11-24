package io.github.christiancg.mpp.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.christiancg.mpp.client.admin.TriggerRunStatus;
import io.github.christiancg.mpp.client.error.ConflictException;
import io.github.christiancg.mpp.client.internal.Descriptors;
import io.github.christiancg.mpp.client.protocol.ErrorCode;
import io.github.christiancg.mpp.client.query.Filters;
import io.github.christiancg.mpp.client.scripting.EntrypointDefinition;
import io.github.christiancg.mpp.client.scripting.EntrypointMethod;
import io.github.christiancg.mpp.client.scripting.MiddlewareCriteria;
import io.github.christiancg.mpp.client.scripting.MiddlewareDefinition;
import io.github.christiancg.mpp.client.scripting.MiddlewareMatch;
import io.github.christiancg.mpp.client.scripting.MiddlewarePhase;
import io.github.christiancg.mpp.client.scripting.ProcedureDefinition;
import io.github.christiancg.mpp.client.scripting.ScheduleDefinition;
import io.github.christiancg.mpp.client.scripting.TriggerDefinition;
import io.github.christiancg.mpp.client.scripting.TriggerEvent;
import io.github.christiancg.mpp.testsupport.ProtocolFixtures;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

public class FacadeDecodingTest {
    private final Recorder recorder = new Recorder();

    @BeforeEach
    public void replyWithFixtures() {
        final Map<String, ObjectNode> byType = new HashMap<>();
        ProtocolFixtures.responses().stream()
                .filter(fixture -> fixture.name().startsWith("ok.") && !fixture.name().equals("ok.aggregate_count"))
                .forEach(fixture -> {
                    final var frame = (ObjectNode) fixture.body().get("frame");
                    byType.putIfAbsent(frame.get("type").asString(), frame);
                });
        recorder.channel.replyWith(request -> byType.get(request.get("type").asString()).deepCopy());
    }

    @Test
    public void test_data_replies_decode() {
        final var saved = recorder.orders.save(Document.of("_id", "o-1"));
        assertEquals("o-1", saved.id());
        assertTrue(saved.inserted());
        final var bulk = recorder.orders.saveAll(List.of(Document.of("_id", "o-1")));
        assertEquals(List.of("o-2"), bulk.inserted());
        assertEquals(List.of("o-1"), bulk.updated());
        assertEquals("o-1", recorder.orders.findById("o-1").orElseThrow().id().orElseThrow());
        assertEquals(2, recorder.orders.query().toList().size());
        assertEquals("o-1", recorder.orders.query().first().orElseThrow().id().orElseThrow());
        assertEquals("o-1", recorder.orders.query().toDocuments().get(0).id().orElseThrow());
        final var analysis = recorder.orders.query().analyze();
        assertEquals(2, analysis.results().size());
        assertEquals("shop", recorder.orders.projectName());
        assertEquals("orders", recorder.orders.name());
    }

    @Test
    public void test_a_conditional_delete_reply_decodes_its_count() {
        final var frame = (ObjectNode) ProtocolFixtures.responses().stream()
                .filter(fixture -> fixture.name().equals("ok.delete_where")).findFirst().orElseThrow().body()
                .get("frame");
        recorder.channel.replyWith(request -> frame.deepCopy());
        assertEquals(3, recorder.orders.deleteWhere(Filters.field("status").eq("archived")));
    }

    @Test
    public void test_an_insert_reply_decodes_like_a_save_and_a_taken_id_raises_a_conflict() {
        final var inserted = recorder.orders.insert(Document.of("_id", "o-1", "status", "new"));
        assertEquals("o-1", inserted.id());
        assertTrue(inserted.inserted());
        assertTrue(recorder.channel.last().get("failOnUpdate").asBoolean());
        final var frame = (ObjectNode) ProtocolFixtures.responses().stream()
                .filter(fixture -> fixture.name().equals("errors.409_18")).findFirst().orElseThrow().body()
                .get("frame");
        recorder.channel.replyWith(request -> frame.deepCopy());
        final var error = assertThrows(ConflictException.class,
                () -> recorder.orders.insert(Document.of("_id", "o-1", "status", "new")));
        assertTrue(error.hasCode(ErrorCode.DOCUMENT_ALREADY_EXISTS));
        assertFalse(ErrorCode.DOCUMENT_ALREADY_EXISTS.isRetryable());
    }

    @Test
    public void test_a_conditional_update_reply_decodes_its_count_and_maps_its_patch() {
        final var frame = (ObjectNode) ProtocolFixtures.responses().stream()
                .filter(fixture -> fixture.name().equals("ok.save_where")).findFirst().orElseThrow().body()
                .get("frame");
        recorder.channel.replyWith(request -> frame.deepCopy());
        final var pending = Filters.field("status").eq("pending");
        assertEquals(3, recorder.orders.updateWhere(pending, Map.of("status", "expired")));
        assertEquals("expired", recorder.channel.last().get("object").get("status").asString());
        assertEquals(3, recorder.orders.updateWhere(pending, new Expiry("o-1", "expired")));
        final var mapped = recorder.channel.last().get("object");
        assertEquals("o-1", mapped.get("_id").asString());
        assertFalse(mapped.has("id"));
        assertEquals("pending", recorder.channel.last().get("condition").get("value").asString());
    }

    @Test
    public void test_ddl_index_and_schema_replies_decode() {
        assertEquals(List.of("orders"), recorder.shop.listCollections());
        assertEquals("shop", recorder.shop.name());
        assertEquals(List.of("email"), recorder.orders.indexes().reindex("email").rebuiltFields());
        assertTrue(recorder.orders.indexes().reindex().primaryKeyEntries().isEmpty());
        assertEquals(List.of("w"), recorder.orders.schema().save(Document.of("type", "object")));
        assertEquals("object", recorder.orders.schema().get().orElseThrow().getString("type").orElseThrow());
        assertEquals(1,
                recorder.shop.stats().getDocument("memory").orElseThrow().getLong("userCacheBytes").orElseThrow());
    }

    @Test
    public void test_scripting_replies_decode() {
        final var run = recorder.shop.runScript("return 1;");
        assertEquals(Map.of("n", 1), run.value());
        assertEquals(List.of("x"), run.logs());
        assertFalse(run.logsTruncated());
        assertTrue(run.runId().isPresent());
        assertEquals(42, recorder.shop.procedures().call("recalc").value());
        assertEquals(4, recorder.shop.procedures().save(ProcedureDefinition.builder("p", "return 1;").build()));
        final var procedure = recorder.shop.procedures().list().get(0);
        assertEquals("recalc", procedure.name());
        assertEquals(4, procedure.version());
        assertTrue(procedure.enabled());
        final var saved = recorder.orders.triggers()
                .save(TriggerDefinition.builder("t", "p").events(TriggerEvent.CREATED).build());
        assertEquals(2, saved.version());
        assertEquals("alice", saved.definer());
        assertEquals("audit", recorder.orders.triggers().list().get(0).name());
        assertEquals("after", recorder.shop.listTriggers().get(0).timing());
        final var tested = recorder.orders.triggers().test("t", TriggerEvent.CREATED, Document.of("_id", "o-1"));
        assertEquals("replace", tested.decision());
        assertTrue(tested.document().isPresent());
        assertEquals(3, recorder.shop.schedules().save(ScheduleDefinition.builder("s", "p").cron("* * * * *").build()));
        assertEquals("nightly", recorder.shop.schedules().list().get(0).name());
    }

    @Test
    public void test_entrypoint_replies_decode() {
        final var saved = recorder.shop.entrypoints()
                .save(EntrypointDefinition.builder("e", EntrypointMethod.GET, "/e").procedureName("p").build());
        assertEquals(1, saved.version());
        assertEquals(3, saved.procedureVersion());
        final var entrypoint = recorder.shop.entrypoints().list().get(0);
        assertEquals("getOrder", entrypoint.name());
        assertEquals("GET", entrypoint.method());
        assertEquals("/orders/{id}", entrypoint.path());
        assertEquals("/shop/orders/{id}", entrypoint.url());
        assertEquals("", entrypoint.procedureName());
        assertTrue(entrypoint.enabled());
        assertEquals(0, entrypoint.timeoutMs());
        assertEquals(0, entrypoint.version());
        assertTrue(entrypoint.definer().isEmpty());
        assertTrue(entrypoint.description().isEmpty());
        assertTrue(entrypoint.disabledReason().isEmpty());
    }

    @Test
    public void test_an_entrypoint_row_decodes_every_field() {
        final var row = Document.of("name", "e", "method", "POST", "path", "/e", "url", "/shop/e", "procedureName", "p",
                "timeoutMs", 5000, "enabled", false, "version", 3, "definer", "alice", "description", "d",
                "disabledReason", "definer deleted");
        final var entrypoint = Descriptors.entrypoint(row);
        assertEquals("p", entrypoint.procedureName());
        assertEquals(5000, entrypoint.timeoutMs());
        assertFalse(entrypoint.enabled());
        assertEquals(3, entrypoint.version());
        assertEquals("alice", entrypoint.definer().orElseThrow());
        assertEquals("d", entrypoint.description().orElseThrow());
        assertEquals("definer deleted", entrypoint.disabledReason().orElseThrow());
        assertEquals(row, entrypoint.raw());
    }

    @Test
    public void test_middleware_replies_decode() {
        final var saved = recorder.shop.middlewares()
                .save(MiddlewareDefinition.builder("m", MiddlewarePhase.INGRESS).procedureName("p").build());
        assertEquals(1, saved.version());
        assertEquals(2, saved.procedureVersion());
        final var middleware = recorder.shop.middlewares().list().get(0);
        assertEquals("auth", middleware.name());
        assertEquals("INGRESS", middleware.phase());
        assertEquals(10, middleware.order());
        assertEquals("checkToken", middleware.procedureName());
        assertTrue(middleware.match().isEmpty());
        assertTrue(middleware.enabled());
        assertEquals(0, middleware.timeoutMs());
        assertEquals(0, middleware.version());
        assertTrue(middleware.definer().isEmpty());
        assertTrue(middleware.description().isEmpty());
        assertTrue(middleware.disabledReason().isEmpty());
    }

    @Test
    public void test_a_middleware_row_decodes_every_field() {
        final var row = Document.parse("""
                {"name":"cors","phase":"EGRESS","order":-1,"match":{"methods":["POST","DELETE"],"pathPrefix":"/orders",
                "exclude":[{"entrypoints":["listOrders"]},{"methods":["GET"],"pathPrefix":"/orders/public"}]},
                "procedureName":"p","timeoutMs":2000,"enabled":false,"version":3,"definer":"alice","description":"d",
                "disabledReason":"definer deleted"}""");
        final var middleware = Descriptors.middleware(row);
        assertEquals("EGRESS", middleware.phase());
        assertEquals(-1, middleware.order());
        assertEquals(MiddlewareMatch.of(
                MiddlewareCriteria.builder().methods(EntrypointMethod.POST, EntrypointMethod.DELETE)
                        .pathPrefix("/orders").build(),
                MiddlewareCriteria.builder().entrypoints("listOrders").build(),
                MiddlewareCriteria.builder().methods(EntrypointMethod.GET).pathPrefix("/orders/public").build()),
                middleware.match().orElseThrow());
        assertEquals(2000, middleware.timeoutMs());
        assertFalse(middleware.enabled());
        assertEquals(3, middleware.version());
        assertEquals("alice", middleware.definer().orElseThrow());
        assertEquals("d", middleware.description().orElseThrow());
        assertEquals("definer deleted", middleware.disabledReason().orElseThrow());
        assertEquals(row, middleware.raw());
    }

    @Test
    public void test_a_middleware_match_skips_unknown_methods_and_non_object_rules() {
        final var row = Document.parse(
                "{\"name\":\"m\",\"match\":{\"methods\":[\"GET\",\"TRACE\"],\"exclude\":[1,{\"methods\":[\"BREW\"],\"entrypoints\":[\"e\"]}]}}");
        final var match = Descriptors.middleware(row).match().orElseThrow();
        assertEquals(List.of(EntrypointMethod.GET), match.include().methods());
        assertEquals(1, match.exclude().size());
        assertTrue(match.exclude().get(0).methods().isEmpty());
        assertEquals(List.of("e"), match.exclude().get(0).entrypoints());
    }

    @Test
    public void test_a_middleware_match_without_exclude_decodes_to_an_empty_exclude() {
        final var row = Document.parse("{\"name\":\"m\",\"match\":{\"pathPrefix\":\"/a\"}}");
        final var match = Descriptors.middleware(row).match().orElseThrow();
        assertEquals("/a", match.include().pathPrefix().orElseThrow());
        assertTrue(match.include().methods().isEmpty());
        assertTrue(match.include().entrypoints().isEmpty());
        assertTrue(match.exclude().isEmpty());
    }

    @Test
    public void test_admin_replies_decode() {
        final var scripts = recorder.admin.scripts().list();
        assertEquals("RUN_SCRIPT", scripts.get(0).kind());
        assertTrue(recorder.admin.scripts().cancel("r"));
        final var runs = recorder.admin.triggerRuns().list(TriggerRunStatus.DEAD);
        assertEquals("DEAD", runs.get(0).status());
        assertTrue(recorder.admin.triggerRuns().resolve("r",
                io.github.christiancg.mpp.client.admin.TriggerRunDecision.REPLAY));
        final var users = recorder.admin.users().list();
        assertEquals("alice", users.get(0).username());
        assertTrue(users.get(0).admin());
        final var transactions = recorder.admin.transactions().list();
        assertEquals(42000, transactions.get(0).ageMs());
    }

    private record Expiry(String id, String status) {
    }
}
