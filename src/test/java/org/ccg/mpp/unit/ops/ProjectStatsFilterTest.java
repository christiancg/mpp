package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.ccg.mpp.bckg_ops.BackgroundTaskManager;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.PkIndexEntry;
import org.ccg.mpp.data.admin.AdminCollEntry;
import org.ccg.mpp.data.admin.AdminProjectEntry;
import org.ccg.mpp.data.admin.AdminUserEntry;
import org.ccg.mpp.data.auth.PermissionLevel;
import org.ccg.mpp.data.auth.ScriptPermissionLevel;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.ProjectStatsHelper;
import org.ccg.mpp.ops.resp.GetProjectStatsResponse;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ProjectStatsFilterTest {
    private static final List<String> NODE_SECTIONS = List.of("memory", "inDoubtTransactions", "triggers", "schedules",
            "scripts", "background");

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.discardQueuedBackgroundEvents(IocContainer.get(BackgroundTaskManager.class));
        AdminOperationHelper.saveProjectEntry(
                new AdminProjectEntry("stats_shop", new ArrayList<>(), new ArrayList<>(List.of("stats_owner"))));
        AdminOperationHelper.saveProjectEntry(new AdminProjectEntry("stats_crm"));
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry("stats_shop", "orders", Set.of("customer")));
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry("stats_shop", "items"));
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry("stats_crm", "leads", Set.of("score", "region")));
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry("stats_crm", "notes"));
        saveUser("stats_admin", true, Map.of(), Map.of(), Map.of());
        saveUser("stats_owner", false, Map.of(), Map.of(), Map.of());
        saveUser("stats_reader", false, Map.of("stats_crm", PermissionLevel.READ), Map.of(), Map.of());
        saveUser("stats_collgrant", false, Map.of(), Map.of("stats_crm|leads", PermissionLevel.READ), Map.of());
        saveUser("stats_scripter", false, Map.of(), Map.of(), Map.of("stats_crm", ScriptPermissionLevel.MANAGE));
        saveUser("stats_nobody", false, Map.of(), Map.of(), Map.of());
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static void saveUser(String name, boolean admin, Map<String, PermissionLevel> projects,
            Map<String, PermissionLevel> collections, Map<String, ScriptPermissionLevel> scripts) throws Exception {
        AdminOperationHelper.saveUserEntry(new AdminUserEntry(name, "hash", admin, new HashSet<>(),
                new HashMap<>(projects), new HashMap<>(collections), new HashMap<>(scripts)));
    }

    private static JsonObject statsFor(String username) {
        return assertInstanceOf(GetProjectStatsResponse.class, ProjectStatsHelper.processGetProjectStats(username))
                .getStats();
    }

    private static Map<String, List<String>> collectionsByProject(JsonObject stats) {
        final var found = new LinkedHashMap<String, List<String>>();
        for (final var project : stats.get("projects").asJsonArray()) {
            final var names = new ArrayList<String>();
            for (final var collection : project.asJsonObject().get("collections").asJsonArray()) {
                names.add(collection.asJsonObject().get("name").asJsonString().getValue());
            }
            found.put(project.asJsonObject().get("name").asJsonString().getValue(), names);
        }
        return found;
    }

    private static void assertNoNodeSections(JsonObject stats) {
        for (final var section : NODE_SECTIONS) {
            assertFalse(stats.has(section), section);
        }
        assertFalse(stats.get("totals").asJsonObject().has("userCount"));
    }

    private static long total(JsonObject stats, String field) {
        return stats.get("totals").asJsonObject().get(field).asJsonNumber().getValue().longValue();
    }

    @Test
    public void test_admin_gets_the_full_report() {
        final var stats = statsFor("stats_admin");

        for (final var section : NODE_SECTIONS) {
            assertTrue(stats.has(section), section);
        }
        assertTrue(stats.get("totals").asJsonObject().has("userCount"));
        assertEquals(Map.of("stats_shop", List.of("items", "orders"), "stats_crm", List.of("leads", "notes")),
                collectionsByProject(stats));
    }

    private static long field(JsonObject stats, String section, String name) {
        return stats.get(section).asJsonObject().get(name).asJsonNumber().getValue().longValue();
    }

    @Test
    public void test_pending_runs_count_the_resident_trigger_run_rows_without_reading_them() {
        final var cache = IocContainer.get(Cache.class);
        for (var i = 0; i < 3; i++) {
            cache.putPkIndexTriggerRun(new PkIndexEntry(Globals.ADMIN_PROJECT_NAME,
                    Globals.ADMIN_TRIGGER_RUNS_COLLECTION_NAME, "missing-run|" + i, 0, 10, 0));
        }

        final var stats = statsFor("stats_admin");

        assertEquals(3L, field(stats, "triggers", "pendingRuns"));
        assertEquals(0L, field(stats, "background", "maintenance"));
        assertTrue(field(stats, "background", "pending") >= 0L);
        assertEquals(0L, field(stats, "scripts", "historyQueued"));
    }

    @Test
    public void test_owner_gets_their_project_with_every_collection_and_no_node_sections() {
        final var stats = statsFor("stats_owner");

        assertEquals(Map.of("stats_shop", List.of("items", "orders")), collectionsByProject(stats));
        assertNoNodeSections(stats);
    }

    @Test
    public void test_project_read_grant_sees_that_project() {
        assertEquals(Map.of("stats_crm", List.of("leads", "notes")), collectionsByProject(statsFor("stats_reader")));
    }

    @Test
    public void test_collection_only_grant_sees_only_that_collection() {
        assertEquals(Map.of("stats_crm", List.of("leads")), collectionsByProject(statsFor("stats_collgrant")));
    }

    @Test
    public void test_script_only_grant_sees_the_project_with_no_collections() {
        assertEquals(Map.of("stats_crm", List.of()), collectionsByProject(statsFor("stats_scripter")));
    }

    @Test
    public void test_totals_cover_only_the_visible_view() {
        final var stats = statsFor("stats_collgrant");

        assertEquals(1L, total(stats, "projectCount"));
        assertEquals(1L, total(stats, "collectionCount"));
        assertEquals(2L, total(stats, "indexCount"));
        assertNoNodeSections(stats);
        final var crm = stats.get("projects").asJsonArray().get(0).asJsonObject();
        assertEquals(1L, crm.get("collectionCount").asJsonNumber().getValue().longValue());
    }

    @Test
    public void test_no_grant_sees_no_projects() {
        final var stats = statsFor("stats_nobody");

        assertEquals(Map.of(), collectionsByProject(stats));
        assertEquals(0L, total(stats, "projectCount"));
    }

    @Test
    public void test_unknown_or_null_user_gets_an_empty_view() {
        for (final var stats : List.of(statsFor(null), statsFor("deleted_user"))) {
            assertEquals(Map.of(), collectionsByProject(stats));
            assertNoNodeSections(stats);
        }
    }
}
