package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.ccg.mpp.cache.UserCache;
import org.ccg.mpp.cache.WholeLoads;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.data.DbEntry;
import org.ccg.mpp.data.PkIndexEntry;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.fs.OutsideWrites;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.PipelineContext;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class PipelinePinnedPkTest {
    private static final String PROJECT = TestGlobals.PROJECT;
    private static final String COLL = TestGlobals.COLL;

    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final Configuration configuration = Configuration.getInstance();
    private Long savedMaxMemory;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
        savedMaxMemory = TestUtils.getPrivateField(configuration, "maxMemoryBytes", Long.class);
        final var entries = new ArrayList<DbEntry>();
        for (var i = 0; i < 200; i++) {
            final var data = new JsonObject();
            data.addProperty("_id", "doc-" + i);
            entries.add(DbEntry.fromJsonObject(PROJECT, COLL, data));
        }
        IocContainer.get(FileSystem.class).bulkInsertIntoCollection(PROJECT, COLL, entries);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", savedMaxMemory);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static PipelineContext context() {
        return PipelineContext.of(0L, Long.MAX_VALUE, null);
    }

    private static int pinnedCount(PipelineContext context) throws IOException {
        final var pinned = context.pinnedPkIndex(PROJECT, COLL);
        assertNotNull(pinned);
        return pinned.size();
    }

    private static WholeLoads wholeLoads() throws Exception {
        return TestUtils.getPrivateField(IocContainer.get(UserCache.class), "wholeLoads", WholeLoads.class);
    }

    private void tinyCache() throws Exception {
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", 1024L);
    }

    @Test
    void aReaderWithoutTheCollectionLockGetsNoPin() throws Exception {
        try (var context = context()) {
            assertNull(context.pinnedPkIndex(PROJECT, COLL));
        }
    }

    private static List<PkIndexEntry> pinAfterPointScans(PipelineContext context) throws IOException {
        for (var fetch = 0; fetch < PipelineContext.POINT_SCANS_BEFORE_PIN; fetch++) {
            assertNull(context.pinnedPkIndex(PROJECT, COLL), "a refused pk is point-scanned for the first fetches");
        }
        return context.pinnedPkIndex(PROJECT, COLL);
    }

    @Test
    void refusedPkIsNotPinnedAndRowsAreScanned() throws Exception {
        tinyCache();
        final var userCache = IocContainer.get(UserCache.class);
        final var fs = IocContainer.get(FileSystem.class);
        final var spied = spy(fs);
        TestUtils.setPrivateField(userCache, "fs", spied);
        locks.lockRead(PROJECT, COLL);
        try (var context = context()) {
            final var found = userCache.getEntriesByIds(PROJECT, COLL, Set.of("doc-1", "doc-7"),
                    context::pinnedPkIndex);
            assertEquals(Set.of("doc-1", "doc-7"),
                    found.stream().map(DbEntry::get_id).collect(java.util.stream.Collectors.toSet()));
            assertEquals(0, wholeLoads().pinned(), "nothing is pinned for a pk served by point scans");
        } finally {
            locks.releaseRead(PROJECT, COLL);
            TestUtils.setPrivateField(userCache, "fs", fs);
        }
        verify(spied, never()).readWholePkIndexFile(PROJECT, COLL);
        verify(spied, times(1)).pkRowsFor(eq(PROJECT), eq(COLL), anyCollection());
    }

    @Test
    void aLockHolderLoadsOncePerPipeline() throws Exception {
        tinyCache();
        locks.lockRead(PROJECT, COLL);
        try (var context = context()) {
            final var first = pinAfterPointScans(context);
            assertNotNull(first);
            assertEquals(200, first.size());
            assertSame(first, context.pinnedPkIndex(PROJECT, COLL), "a transient list is pinned for the pipeline");
            assertEquals(1, wholeLoads().pinned());
        } finally {
            locks.releaseRead(PROJECT, COLL);
        }
        assertEquals(0, wholeLoads().pinned(), "closing the pipeline gives its pin back");
    }

    @Test
    void aResidentListIsPinnedWithoutAPermit() throws Exception {
        locks.lockRead(PROJECT, COLL);
        try (var context = context()) {
            final var first = context.pinnedPkIndex(PROJECT, COLL);
            assertSame(first, context.pinnedPkIndex(PROJECT, COLL));
            assertEquals(0, wholeLoads().pinned());
        } finally {
            locks.releaseRead(PROJECT, COLL);
        }
    }

    @Test
    void pinsNeverTakeTheLastTransientPermit() throws Exception {
        tinyCache();
        final var permits = wholeLoads().availablePermits();
        final var contexts = new ArrayList<PipelineContext>();
        locks.lockRead(PROJECT, COLL);
        try {
            for (var pipeline = 0; pipeline < permits; pipeline++) {
                final var context = context();
                contexts.add(context);
                pinAfterPointScans(context);
            }
            assertEquals(permits - 1, wholeLoads().pinned());
            assertEquals(1, wholeLoads().availablePermits(), "one permit stays free for loads");
            assertNull(contexts.getLast().pinnedPkIndex(PROJECT, COLL),
                    "a pipeline that found no free pin point-scans");
        } finally {
            contexts.forEach(PipelineContext::close);
            locks.releaseRead(PROJECT, COLL);
        }
        assertEquals(permits, wholeLoads().availablePermits());
    }

    @Test
    void aFailedLoadIsNotPinned() throws Exception {
        tinyCache();
        final var index = new File(TestGlobals.PATH + "/" + PROJECT + "/" + COLL + "/" + COLL + "-pk.idx");
        final var original = Files.readAllBytes(index.toPath());
        Files.writeString(index.toPath(), "garbage\n", StandardCharsets.UTF_8);
        OutsideWrites.madeTo(index);
        locks.lockRead(PROJECT, COLL);
        try (var context = context()) {
            assertThrows(IOException.class, () -> context.pinnedPkIndex(PROJECT, COLL));
            Files.write(index.toPath(), original);
            OutsideWrites.madeTo(index);
            assertEquals(200, pinnedCount(context));
        } finally {
            locks.releaseRead(PROJECT, COLL);
        }
        assertEquals(0, wholeLoads().pinned());
    }

    @Test
    void separateCollectionsArePinnedSeparately() throws Exception {
        TestUtils.createTestJoinCollection();
        locks.lockRead(PROJECT, COLL);
        locks.lockRead(PROJECT, TestGlobals.JOIN_COLL);
        try (var context = context()) {
            assertEquals(List.of(), context.pinnedPkIndex(PROJECT, TestGlobals.JOIN_COLL));
            assertEquals(200, pinnedCount(context));
        } finally {
            locks.releaseRead(PROJECT, TestGlobals.JOIN_COLL);
            locks.releaseRead(PROJECT, COLL);
        }
    }
}
