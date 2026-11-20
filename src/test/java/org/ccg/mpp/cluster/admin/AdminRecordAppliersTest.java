package org.ccg.mpp.cluster.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.ccg.mpp.bckg_ops.events.EventType;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.admin.AdminCollEntry;
import org.ccg.mpp.data.admin.AdminTriggerRunEntry;
import org.ccg.mpp.ejson.elements.JsonArray;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.TriggerRunLog;
import org.ccg.mpp.ops.admin.AdminRecordKey;
import org.ccg.mpp.ops.admin.StoredDefinitions;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.Test;

public class AdminRecordAppliersTest extends AdminRecordTestBase {

    @Test
    public void test_a_project_record_installs_its_owners_and_keeps_the_local_collections() {
        AdminRecordMerge.apply(List.of(project(TestGlobals.PROJECT, clock.next(), "alice")), true);

        final var installed = cache.getAdminProjectEntry(TestGlobals.PROJECT);
        assertTrue(installed.isOwner("alice"));
        assertTrue(cache.getCollectionNamesForProject(TestGlobals.PROJECT).contains(TestGlobals.COLL));
    }

    @Test
    public void test_a_newer_case_variant_project_replaces_the_local_one() throws Exception {
        final var version = clock.next();

        final var result = AdminRecordMerge.apply(List.of(project("MYPROJECT", version)), true);

        assertTrue(result.complete());
        assertNull(cache.getAdminProjectEntry(TestGlobals.PROJECT));
        assertNotNull(cache.getAdminProjectEntry("MYPROJECT"));
        assertEquals(version, tombstones().get(AdminRecordKey.project(TestGlobals.PROJECT).id()).longValue());
    }

    @Test
    public void test_an_older_case_variant_project_is_buried_under_the_local_one() throws Exception {
        final var local = liveVersion(AdminRecordKey.project(TestGlobals.PROJECT));

        final var result = AdminRecordMerge.apply(List.of(project("MYPROJECT", 2L)), true);

        assertTrue(result.complete());
        assertNotNull(cache.getAdminProjectEntry(TestGlobals.PROJECT));
        assertNull(cache.getAdminProjectEntry("MYPROJECT"));
        assertEquals(local, tombstones().get(AdminRecordKey.project("MYPROJECT").id()).longValue());
    }

    private static void recordPendingRun(String runId) throws Exception {
        AdminOperationHelper.saveTriggerRun(new AdminTriggerRunEntry(runId, 0L, TriggerRunLog.currentNodeId(),
                TestGlobals.PROJECT, TestGlobals.COLL, "audit", "mark", EventType.CREATED, false, "admin", 0,
                System.currentTimeMillis(), List.of("watched"), List.of()));
    }

    @Test
    public void test_a_project_tombstone_from_anti_entropy_quarantines_it() throws Exception {
        recordPendingRun("quarantined-db-run");
        final var result = AdminRecordMerge
                .apply(List.of(tombstone(AdminRecordKey.project(TestGlobals.PROJECT), clock.next())), false);

        assertTrue(result.complete());
        assertNull(cache.getAdminProjectEntry(TestGlobals.PROJECT));
        assertNull(cache.getAdminCollectionEntry(TestGlobals.PROJECT, TestGlobals.COLL));
        assertTrue(TriggerRunLog.recordIdsFor("quarantined-db-run").isEmpty(),
                "a quarantined project keeps no trigger run that could fire into a re-created one");
    }

    @Test
    public void test_a_live_project_tombstone_drops_it() {
        final var result = AdminRecordMerge
                .apply(List.of(tombstone(AdminRecordKey.project(TestGlobals.PROJECT), clock.next())), true);

        assertTrue(result.complete());
        assertNull(cache.getAdminProjectEntry(TestGlobals.PROJECT));
    }

    @Test
    public void test_a_collection_tombstone_from_anti_entropy_quarantines_it() throws Exception {
        recordPendingRun("quarantined-coll-run");
        final var result = AdminRecordMerge.apply(
                List.of(tombstone(AdminRecordKey.collection(TestGlobals.PROJECT, TestGlobals.COLL), clock.next())),
                false);

        assertTrue(result.complete());
        assertNull(cache.getAdminCollectionEntry(TestGlobals.PROJECT, TestGlobals.COLL));
        assertTrue(TriggerRunLog.recordIdsFor("quarantined-coll-run").isEmpty(),
                "a quarantined collection keeps no trigger run that could fire into a re-created one");
    }

    @Test
    public void test_a_collection_record_builds_and_drops_indexes_to_match() throws Exception {
        AdminOperationHelper
                .saveCollectionEntry(new AdminCollEntry(TestGlobals.PROJECT, TestGlobals.COLL, Set.of("old")));

        AdminRecordMerge.apply(List.of(collection(TestGlobals.PROJECT, TestGlobals.COLL, clock.next(), "added")), true);

        assertEquals(Set.of("added"), cache.getIndexesForCollection(TestGlobals.PROJECT, TestGlobals.COLL));
        assertFalse(fs.indexMarkers().isMarked(TestGlobals.PROJECT, TestGlobals.COLL, "added"));
    }

    @Test
    public void test_a_collection_record_carries_its_incarnation_and_the_clock_observes_it() {
        final var incarnation = clock.next() + 1_000_000L;

        AdminRecordMerge.apply(List.of(collection(TestGlobals.PROJECT, "incarnated", clock.next(), incarnation)), true);

        assertEquals(incarnation, cache.getAdminCollectionEntry(TestGlobals.PROJECT, "incarnated").getIncarnation());
        assertTrue(clock.current() >= incarnation);
    }

    @Test
    public void test_a_newer_case_variant_collection_replaces_the_local_one() throws Exception {
        final var version = clock.next();

        final var result = AdminRecordMerge.apply(List.of(collection(TestGlobals.PROJECT, "MYCOLLECTION", version)),
                true);

        assertTrue(result.complete());
        assertNull(cache.getAdminCollectionEntry(TestGlobals.PROJECT, TestGlobals.COLL));
        assertNotNull(cache.getAdminCollectionEntry(TestGlobals.PROJECT, "MYCOLLECTION"));
        assertEquals(version,
                tombstones().get(AdminRecordKey.collection(TestGlobals.PROJECT, TestGlobals.COLL).id()).longValue());
    }

    @Test
    public void test_an_older_case_variant_collection_is_buried_under_the_local_one() throws Exception {
        final var result = AdminRecordMerge.apply(List.of(collection(TestGlobals.PROJECT, "MYCOLLECTION", 2L)), true);

        assertTrue(result.complete());
        assertNotNull(cache.getAdminCollectionEntry(TestGlobals.PROJECT, TestGlobals.COLL));
        assertNull(cache.getAdminCollectionEntry(TestGlobals.PROJECT, "MYCOLLECTION"));
        assertTrue(tombstones().containsKey(AdminRecordKey.collection(TestGlobals.PROJECT, "MYCOLLECTION").id()));
    }

    @Test
    public void test_a_procedure_record_installs_and_its_tombstone_removes_it() throws Exception {
        final var version = clock.next();

        AdminRecordMerge.apply(List.of(procedure(TestGlobals.PROJECT, "proc", version)), true);
        assertEquals(version, liveVersion(AdminRecordKey.procedure(TestGlobals.PROJECT, "proc")));

        AdminRecordMerge.apply(List.of(tombstone(AdminRecordKey.procedure(TestGlobals.PROJECT, "proc"), clock.next())),
                true);
        assertNull(StoredDefinitions.procedure(TestGlobals.PROJECT, "proc"));
    }

    @Test
    public void test_a_schema_record_installs_wrapped_and_its_tombstone_removes_it() throws Exception {
        final var version = clock.next();

        AdminRecordMerge.apply(List.of(schema(TestGlobals.COLL, version)), true);
        assertEquals(version, liveVersion(AdminRecordKey.schema(TestGlobals.PROJECT, TestGlobals.COLL)));
        assertNotNull(cache.getCollectionSchema(TestGlobals.PROJECT, TestGlobals.COLL));

        AdminRecordMerge.apply(
                List.of(tombstone(AdminRecordKey.schema(TestGlobals.PROJECT, TestGlobals.COLL), clock.next())), true);
        assertNull(StoredDefinitions.schema(TestGlobals.PROJECT, TestGlobals.COLL));
    }

    @Test
    public void test_triggers_and_schedule_records_install_and_remove() throws Exception {
        final var triggers = AdminRecords.live(AdminRecordKey.triggers(TestGlobals.PROJECT, TestGlobals.COLL));
        assertNull(triggers);
        final var triggersBody = new JsonObject();
        triggersBody.add("triggers", new JsonArray());
        final var scheduleBody = new JsonObject();
        scheduleBody.addProperty("name", "nightly");
        scheduleBody.addProperty("procedureName", "proc");
        scheduleBody.addProperty("intervalMs", 60_000);

        final var result = AdminRecordMerge.apply(List.of(
                new AdminRecord(AdminRecordKey.triggers(TestGlobals.PROJECT, TestGlobals.COLL), clock.next(),
                        triggersBody),
                new AdminRecord(AdminRecordKey.schedule(TestGlobals.PROJECT, "nightly"), clock.next(), scheduleBody)),
                true);

        assertTrue(result.complete());
        assertNotNull(StoredDefinitions.triggers(TestGlobals.PROJECT, TestGlobals.COLL));
        assertNotNull(StoredDefinitions.schedule(TestGlobals.PROJECT, "nightly"));

        AdminRecordMerge
                .apply(List.of(tombstone(AdminRecordKey.triggers(TestGlobals.PROJECT, TestGlobals.COLL), clock.next()),
                        tombstone(AdminRecordKey.schedule(TestGlobals.PROJECT, "nightly"), clock.next())), true);
        assertNull(StoredDefinitions.triggers(TestGlobals.PROJECT, TestGlobals.COLL));
        assertNull(StoredDefinitions.schedule(TestGlobals.PROJECT, "nightly"));
    }

    @Test
    public void test_a_definition_whose_lock_stays_held_is_retried_later() throws Exception {
        final var locks = IocContainer.get(ResourceLocking.class);
        final var holder = Thread.ofVirtual().start(() -> {
            try {
                locks.lock(TestGlobals.PROJECT, TestGlobals.COLL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        holder.join();
        final var origTimeout = config.getReplicationAckTimeoutMs();
        TestUtils.setPrivateField(config, "replicationAckTimeoutMs", 50L);
        try {
            final var result = AdminRecordMerge.apply(List.of(schema(TestGlobals.COLL, clock.next())), true);

            assertFalse(result.complete());
            assertNull(StoredDefinitions.schema(TestGlobals.PROJECT, TestGlobals.COLL));
        } finally {
            TestUtils.setPrivateField(config, "replicationAckTimeoutMs", origTimeout);
        }
    }

    @Test
    public void test_a_tombstone_is_recorded_even_when_its_removal_cannot_take_the_lock() throws Exception {
        final var locks = IocContainer.get(ResourceLocking.class);
        Thread.ofVirtual().start(() -> {
            try {
                locks.lock(TestGlobals.PROJECT, Globals.COLLECTION_NAMES_LOCK);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).join();
        final var key = AdminRecordKey.collection(TestGlobals.PROJECT, TestGlobals.COLL);
        final var version = clock.next();
        final var origTimeout = config.getReplicationAckTimeoutMs();
        TestUtils.setPrivateField(config, "replicationAckTimeoutMs", 50L);
        try {
            assertFalse(AdminRecordMerge.apply(List.of(tombstone(key, version)), false).complete());
        } finally {
            TestUtils.setPrivateField(config, "replicationAckTimeoutMs", origTimeout);
        }

        assertEquals(version, tombstones().get(key.id()).longValue());
        assertNotNull(cache.getAdminCollectionEntry(TestGlobals.PROJECT, TestGlobals.COLL));
    }

    @Test
    public void test_a_removed_project_that_is_not_there_is_already_removed() throws Exception {
        assertTrue(ProjectRecordApplier.remove(tombstone(AdminRecordKey.project("absent"), clock.next()), true));
        assertTrue(CollectionRecordApplier
                .remove(tombstone(AdminRecordKey.collection(TestGlobals.PROJECT, "absent"), clock.next()), false));
    }
}
