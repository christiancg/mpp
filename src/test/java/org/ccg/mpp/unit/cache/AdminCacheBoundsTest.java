package org.ccg.mpp.unit.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.ccg.mpp.bckg_ops.events.EventType;
import org.ccg.mpp.cache.AdminCache;
import org.ccg.mpp.cache.BoundedLruCache;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.cache.PartitionedMissCache;
import org.ccg.mpp.data.ProcedureDefinition;
import org.ccg.mpp.data.TriggerDefinition;
import org.ccg.mpp.ejson.EJson;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Each test installs its own small-capped caches so eviction is reachable without writing megabytes.
 */
public class AdminCacheBoundsTest {
    private final Cache cache = IocContainer.get(Cache.class);
    private final AdminCache adminCache = IocContainer.get(AdminCache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final EJson eJson = IocContainer.get(EJson.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void clear() throws IOException {
        for (final var name : fs.metadataFiles().listProcedureNames(TestGlobals.PROJECT)) {
            fs.metadataFiles().deleteProcedure(TestGlobals.PROJECT, name);
        }
        cache.removeProceduresForProject(TestGlobals.PROJECT);
        cache.removeCollectionSchemasForProject(TestGlobals.PROJECT);
    }

    @AfterEach
    void restoreDefaults() throws Exception {
        installProcedureCache(32L * 1024 * 1024);
        installMissCache(4096);
        installTriggerCache(4096);
    }

    private void installProcedureCache(long maxBytes) throws Exception {
        TestUtils.setPrivateField(adminCache, "procedures", new BoundedLruCache<ProcedureDefinition>(Integer.MAX_VALUE,
                maxBytes, definition -> (long) definition.getSource().length() * 2L + 512L));
    }

    private void installMissCache(int maxEntries) throws Exception {
        TestUtils.setPrivateField(adminCache, "metadataMisses", new PartitionedMissCache(maxEntries));
    }

    private void installTriggerCache(int maxEntries) throws Exception {
        TestUtils.setPrivateField(adminCache, "triggers", new BoundedLruCache<List<TriggerDefinition>>(maxEntries, 0L,
                definitions -> definitions.size() * 512L + 128L));
    }

    private ProcedureDefinition writeProcedure(String name, String source) throws Exception {
        final var definition = new ProcedureDefinition(name, source, 1L, null, true, 1L, 1L, "alice");
        fs.metadataFiles().writeProcedure(TestGlobals.PROJECT, name, eJson.toJson(definition.toJsonObject()));
        return definition;
    }

    private void writeTriggers() throws Exception {
        final var definition = new TriggerDefinition("t1", java.util.Set.of(EventType.CREATED), "proc",
                TriggerDefinition.MODE_DOCUMENT, false, true, "alice", 1L, 1L, 1L, "alice");
        fs.metadataFiles().writeTriggers(TestGlobals.PROJECT, TestGlobals.COLL,
                eJson.toJson(TriggerDefinition.toFileJson(List.of(definition))));
    }

    @Test
    public void test_procedure_cache_evicts_under_byte_cap() throws Exception {
        installProcedureCache(2048L);
        writeProcedure("p1", "x".repeat(400));
        writeProcedure("p2", "y".repeat(400));
        writeProcedure("p3", "z".repeat(400));
        assertNotNull(cache.getProcedure(TestGlobals.PROJECT, "p1"));
        assertNotNull(cache.getProcedure(TestGlobals.PROJECT, "p2"));
        assertNotNull(cache.getProcedure(TestGlobals.PROJECT, "p3"));
        final var stats = cache.metadataCacheStats();
        assertTrue(stats.procedureBytes() <= 2048L, "expected the cache to stay under its byte cap");
        assertTrue(stats.procedureEntries() < 3, "expected at least one procedure to have been evicted");
        assertNotNull(cache.getProcedure(TestGlobals.PROJECT, "p1"));
    }

    @Test
    public void test_miss_flood_does_not_evict_live_procedures() throws Exception {
        installProcedureCache(32L * 1024 * 1024);
        installMissCache(16);
        writeProcedure("live", "return 1;");
        assertNotNull(cache.getProcedure(TestGlobals.PROJECT, "live"));
        for (var i = 0; i < 500; i++) {
            assertNull(cache.getProcedure(TestGlobals.PROJECT, "ghost" + i));
        }
        assertEquals(1, cache.metadataCacheStats().procedureEntries());
    }

    @Test
    public void test_miss_cache_is_itself_bounded() throws Exception {
        installMissCache(16);
        for (var i = 0; i < 500; i++) {
            assertNull(cache.getProcedure(TestGlobals.PROJECT, "ghost" + i));
        }
        assertTrue(cache.metadataCacheStats().missEntries() <= 16,
                "expected the miss cache to stay at its cap, was " + cache.metadataCacheStats().missEntries());
    }

    @Test
    public void missesArePartitionedPerProject() throws Exception {
        assertNull(cache.getProcedure(TestGlobals.PROJECT, "mine"));
        writeProcedure("mine", "return 1;");
        for (var i = 0; i < 2000; i++) {
            assertNull(cache.getProcedure("floodProject", "ghost" + i));
        }
        assertNull(cache.getProcedure(TestGlobals.PROJECT, "mine"),
                "another project's misses must not flush this project's remembered miss");
        assertTrue(cache.metadataCacheStats().missEntries() <= PartitionedMissCache.MAX_ENTRIES_PER_PROJECT + 1);
        cache.removeProcedure(TestGlobals.PROJECT, "mine");
        assertNotNull(cache.getProcedure(TestGlobals.PROJECT, "mine"));
    }

    @Test
    public void test_remove_procedure_clears_the_miss_entry() throws Exception {
        assertNull(cache.getProcedure(TestGlobals.PROJECT, "later"));
        writeProcedure("later", "return 1;");
        cache.removeProcedure(TestGlobals.PROJECT, "later");
        assertNotNull(cache.getProcedure(TestGlobals.PROJECT, "later"));
    }

    @Test
    public void test_put_procedure_clears_the_miss_entry() throws Exception {
        assertNull(cache.getProcedure(TestGlobals.PROJECT, "fresh"));
        final var definition = writeProcedure("fresh", "return 1;");
        cache.putProcedure(TestGlobals.PROJECT, definition);
        assertEquals(definition, cache.getProcedure(TestGlobals.PROJECT, "fresh"));
    }

    @Test
    public void test_remove_procedures_for_project_clears_miss_entries() throws Exception {
        assertNull(cache.getProcedure(TestGlobals.PROJECT, "gone"));
        writeProcedure("gone", "return 1;");
        cache.removeProceduresForProject(TestGlobals.PROJECT);
        assertNotNull(cache.getProcedure(TestGlobals.PROJECT, "gone"));
    }

    @Test
    public void test_schema_miss_is_cleared_by_put() {
        assertNull(cache.getCollectionSchema(TestGlobals.PROJECT, TestGlobals.COLL));
        final var schema = new JsonObject();
        schema.add("type", new JsonString("object"));
        cache.putCollectionSchema(TestGlobals.PROJECT, TestGlobals.COLL, schema);
        assertEquals(schema, cache.getCollectionSchema(TestGlobals.PROJECT, TestGlobals.COLL));
    }

    @Test
    public void test_schema_and_procedure_misses_do_not_collide() {
        // Both miss caches share one store, so their keys must be namespaced apart: a missing procedure named
        // like the collection must not be reported as a missing schema.
        assertNull(cache.getProcedure(TestGlobals.PROJECT, TestGlobals.COLL));
        final var schema = new JsonObject();
        schema.add("type", new JsonString("object"));
        cache.putCollectionSchema(TestGlobals.PROJECT, TestGlobals.COLL, schema);
        assertEquals(schema, cache.getCollectionSchema(TestGlobals.PROJECT, TestGlobals.COLL));
        assertNull(cache.getProcedure(TestGlobals.PROJECT, TestGlobals.COLL));
    }

    @Test
    public void test_trigger_cache_evicts_under_entry_cap() throws Exception {
        installTriggerCache(1);
        writeTriggers();
        assertEquals(1, cache.getTriggersFor(TestGlobals.PROJECT, TestGlobals.COLL).size());
        cache.getTriggersFor(TestGlobals.PROJECT, "other");
        assertEquals(1, cache.metadataCacheStats().triggerEntries());
        assertEquals(1, cache.getTriggersFor(TestGlobals.PROJECT, TestGlobals.COLL).size());
        fs.metadataFiles().deleteTriggers(TestGlobals.PROJECT, TestGlobals.COLL);
        cache.removeTriggers(TestGlobals.PROJECT, TestGlobals.COLL);
    }

    @Test
    public void test_metadata_cache_stats_report_the_live_footprint() throws Exception {
        writeProcedure("counted", "return 1;");
        assertNotNull(cache.getProcedure(TestGlobals.PROJECT, "counted"));
        final var stats = cache.metadataCacheStats();
        assertEquals(1, stats.procedureEntries());
        assertTrue(stats.procedureBytes() > 0L);
    }
}
