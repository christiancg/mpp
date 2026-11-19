package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.ccg.mpp.bckg_ops.events.EventType;
import org.ccg.mpp.bckg_ops.events.TriggerEvent;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.TriggerDefinition;
import org.ccg.mpp.ejson.EJson;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.TriggerDispatcher;
import org.ccg.mpp.ops.TriggerRunLog;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TriggerUnreadableDefinitionTest {
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final EJson eJson = IocContainer.get(EJson.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.setPrivateField(Configuration.getInstance(), "triggerRunLogEnabled", true);
        TestUtils.createTestProjectAndCollection();
    }

    @AfterAll
    static void tearDown() throws Exception {
        IocContainer.get(Cache.class).removeTriggers(TestGlobals.PROJECT, TestGlobals.COLL);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void clear() throws Exception {
        fs.metadataFiles().deleteTriggers(TestGlobals.PROJECT, TestGlobals.COLL);
        cache.removeTriggers(TestGlobals.PROJECT, TestGlobals.COLL);
    }

    private File triggersFile() {
        return new File(TestGlobals.PATH + File.separator + TestGlobals.PROJECT + File.separator + TestGlobals.COLL
                + File.separator + TestGlobals.COLL + "-triggers.json");
    }

    private void writeTriggers(TriggerDefinition definition) throws java.io.IOException {
        final var wrapper = new org.ccg.mpp.ejson.elements.JsonObject();
        final var array = new org.ccg.mpp.ejson.elements.JsonArray();
        array.add(definition.toJsonObject());
        wrapper.add("triggers", array);
        fs.metadataFiles().writeTriggers(TestGlobals.PROJECT, TestGlobals.COLL, eJson.toJson(wrapper));
        cache.removeTriggers(TestGlobals.PROJECT, TestGlobals.COLL);
    }

    private static TriggerDefinition definition() {
        return new TriggerDefinition("t1", new LinkedHashSet<>(Set.of(EventType.CREATED)), "p",
                TriggerDefinition.MODE_DOCUMENT, false, true, "owner", 1L, 1L, 1L, "owner");
    }

    private static TriggerEvent eventFor(String runId) {
        return new TriggerEvent(EventType.CREATED, TestGlobals.PROJECT, TestGlobals.COLL, "t1", "p", false, List.of(),
                "owner", 0, runId);
    }

    private static String recordRun() {
        return TriggerRunLog.record(new TriggerRunLog.TriggerRunDescriptor(TestGlobals.PROJECT, TestGlobals.COLL, "t1",
                "p", EventType.CREATED, false, "owner", 0, System.currentTimeMillis(), List.of()));
    }

    @Test
    public void test_an_unreadable_trigger_file_keeps_the_pending_run() throws Exception {
        writeTriggers(definition());
        final var runId = recordRun();
        org.junit.jupiter.api.Assumptions.assumeTrue(runId != null, "the run log must be enabled for this case");
        final var file = triggersFile();
        assertTrue(file.delete());
        assertTrue(file.mkdirs(), "a directory in the file's place makes the read fail rather than report absence");
        try {
            TriggerDispatcher.dispatch(eventFor(runId));

            assertFalse(TriggerRunLog.recordIdsFor(runId).isEmpty(),
                    "the dispatcher cannot tell an unreadable definition from a deleted one, and consuming the"
                            + " record deletes the only thing that would have replayed the run");
        } finally {
            assertTrue(file.delete());
        }
    }

    @Test
    public void test_a_corrupt_trigger_file_also_keeps_the_pending_run() throws Exception {
        writeTriggers(definition());
        final var runId = recordRun();
        org.junit.jupiter.api.Assumptions.assumeTrue(runId != null, "the run log must be enabled for this case");
        fs.metadataFiles().writeTriggers(TestGlobals.PROJECT, TestGlobals.COLL, "definitely not json");
        cache.removeTriggers(TestGlobals.PROJECT, TestGlobals.COLL);

        TriggerDispatcher.dispatch(eventFor(runId));

        assertFalse(TriggerRunLog.recordIdsFor(runId).isEmpty(),
                "a torn definition file is what an unclean stop leaves behind, and reading it as 'no such"
                        + " trigger' consumes the only record that would have replayed the run");
    }

    @Test
    public void test_a_genuinely_deleted_trigger_still_consumes_the_run() throws Exception {
        fs.metadataFiles().deleteTriggers(TestGlobals.PROJECT, TestGlobals.COLL);
        cache.removeTriggers(TestGlobals.PROJECT, TestGlobals.COLL);
        final var runId = recordRun();
        org.junit.jupiter.api.Assumptions.assumeTrue(runId != null, "the run log must be enabled for this case");

        TriggerDispatcher.dispatch(eventFor(runId));

        assertTrue(TriggerRunLog.recordIdsFor(runId).isEmpty(),
                "a trigger that really is gone must still consume its pending run");
    }

    @Test
    public void test_a_procedures_folder_that_cannot_be_listed_keeps_the_pending_run() throws Exception {
        writeTriggers(definition());
        final var runId = recordRun();
        org.junit.jupiter.api.Assumptions.assumeTrue(runId != null, "the run log must be enabled for this case");
        final var folder = new File(
                TestGlobals.PATH + File.separator + TestGlobals.PROJECT + File.separator + Globals.PROCEDURES_FOLDER);
        if (folder.exists()) {
            TestUtils.deleteFolder(folder);
        }
        Files.writeString(folder.toPath(), "x", StandardCharsets.UTF_8);
        cache.removeProceduresForProject(TestGlobals.PROJECT);
        try {
            TriggerDispatcher.dispatch(eventFor(runId));

            assertFalse(TriggerRunLog.recordIdsFor(runId).isEmpty(),
                    "a folder listing that failed read as a deleted procedure, so the run was consumed");
        } finally {
            Files.delete(folder.toPath());
            cache.removeProceduresForProject(TestGlobals.PROJECT);
        }
    }
}
