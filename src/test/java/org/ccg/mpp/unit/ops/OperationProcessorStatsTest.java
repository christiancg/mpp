package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.conn.ClientTracker;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.ScriptAdmission;
import org.ccg.mpp.ops.req.GetProjectStatsRequest;
import org.ccg.mpp.ops.req.SaveRequest;
import org.ccg.mpp.ops.resp.GetProjectStatsResponse;
import org.ccg.mpp.ops.resp.OperationResponse;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class OperationProcessorStatsTest {
    final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

    @BeforeAll
    static void setUpBeforeClass() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
    }

    @AfterAll
    public static void tearDownAll() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private OperationResponse statsAsAdmin() {
        TestUtils.ensureAdminUser("ddl_stats_admin");
        final var clientTracker = IocContainer.get(ClientTracker.class);
        final var clientId = clientTracker.registerForwardedClient("ddl_stats_admin");
        try {
            return processor.processMessage(new GetProjectStatsRequest(), clientId);
        } finally {
            clientTracker.removeById(clientId);
        }
    }

    @Test
    public void test_stats_without_a_user_has_no_node_sections() {
        final var response = (GetProjectStatsResponse) processor.processMessage(new GetProjectStatsRequest());

        assertEquals(OperationStatus.OK, response.getStatus());
        assertFalse(response.getStats().has("memory"));
        assertFalse(response.getStats().has("scripts"));
        assertEquals(0, response.getStats().get("projects").asJsonArray().size());
    }

    @Test
    public void test_get_project_stats_returns_populated_payload() {
        final var save = new SaveRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        final var obj = new JsonObject();
        obj.addProperty(Globals.PK_FIELD, "stats_seed");
        obj.addProperty("v", 1);
        save.setObject(obj);
        save.set_id("stats_seed");
        processor.processMessage(save);

        final var response = (GetProjectStatsResponse) statsAsAdmin();
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(OperationType.GET_PROJECT_STATS, response.getType());
        final var stats = response.getStats();
        assertNotNull(stats);
        assertTrue(stats.has("memory"));
        assertTrue(stats.has("totals"));
        assertTrue(stats.has("projects"));
        assertTrue(stats.has("scripts"));

        final var scripts = stats.get("scripts").asJsonObject();
        assertTrue(scripts.has("routingEnabled"));
        assertEquals(0L, scripts.get("running").asJsonNumber().getValue().longValue());
        final var admission = IocContainer.get(ScriptAdmission.class);
        assertEquals(admission.capacity(), scripts.get("capacity").asJsonNumber().getValue().longValue());
        assertEquals(admission.available(), scripts.get("available").asJsonNumber().getValue().longValue());
        assertEquals(admission.getRejected(), scripts.get("rejected").asJsonNumber().getValue().longValue());
        assertEquals(admission.getWaited(), scripts.get("waited").asJsonNumber().getValue().longValue());
        assertTrue(scripts.has("forwarded"));
        assertTrue(scripts.has("forwardFallbacks"));
        assertEquals(Configuration.getInstance().getScriptLocalityWeight(),
                scripts.get("localityWeight").asJsonNumber().getValue().intValue());
        assertTrue(scripts.has("localityPreferred"));
        assertEquals(IocContainer.get(org.ccg.mpp.ops.ScriptRunRegistry.class).getCancelled(),
                scripts.get("cancelled").asJsonNumber().getValue().longValue());

        final var memory = stats.get("memory").asJsonObject();
        assertTrue(memory.has("heapUsedBytes"));
        assertTrue(memory.has("heapMaxBytes"));
        assertTrue(memory.has("userCacheBytes"));
        assertTrue(memory.has("maxMemoryBytes"));

        final var totals = stats.get("totals").asJsonObject();
        assertTrue(totals.get("userCount").asJsonNumber().getValue().longValue() >= 0L);
        assertTrue(totals.get("projectCount").asJsonNumber().getValue().longValue() >= 1L);
        assertTrue(totals.get("collectionCount").asJsonNumber().getValue().longValue() >= 1L);
        assertTrue(totals.get("entryCount").asJsonNumber().getValue().longValue() >= 0L);

        final var projects = stats.get("projects").asJsonArray().asList();
        assertFalse(projects.isEmpty(), "expected at least one user project in the stats payload");
        final var firstProject = projects.getFirst().asJsonObject();
        assertTrue(firstProject.has("name"));
        assertTrue(firstProject.has("collectionCount"));
        assertTrue(firstProject.has("collections"));
        final var firstCollections = firstProject.get("collections").asJsonArray().asList();
        if (!firstCollections.isEmpty()) {
            final var firstColl = firstCollections.getFirst().asJsonObject();
            assertTrue(firstColl.has("name"));
            assertTrue(firstColl.has("indexCount"));
            assertTrue(firstColl.has("indexes"));
            assertTrue(firstColl.has("pageCount"));
            assertTrue(firstColl.has("entryCount"));
            assertTrue(firstColl.has("sizeBytes"));
        }
    }
}
