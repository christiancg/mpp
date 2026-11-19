package org.ccg.mpp.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.ccg.mpp.bckg_ops.BackgroundTaskManager;
import org.ccg.mpp.bckg_ops.PendingIndexWrites;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.PkIndexEntry;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.SaveOperationHelper;
import org.ccg.mpp.ops.admin.PageOccupancyReconciler;
import org.ccg.mpp.ops.req.SaveRequest;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class PageOccupancyReconcilerParseTest {
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);
    private BackgroundTaskManager originalTaskManager;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
        originalTaskManager = TestUtils.getPrivateStaticField(PageOccupancyReconciler.class, "taskManager",
                BackgroundTaskManager.class);
        TestUtils.setPrivateStaticField(PageOccupancyReconciler.class, "taskManager", new BackgroundTaskManager());
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateStaticField(PageOccupancyReconciler.class, "taskManager", originalTaskManager);
        pendingIndexWrites.clearCollection(TestGlobals.PROJECT, TestGlobals.COLL);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static void save(String id) throws Exception {
        final var object = new JsonObject();
        object.addProperty(Globals.PK_FIELD, id);
        object.addProperty("payload", "value-" + id);
        final var request = new SaveRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        request.setObject(object);
        request.set_id(id);
        assertNotNull(SaveOperationHelper.executeSave(request));
    }

    private static Path collectionFile(String suffix) {
        return new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.PROJECT + Globals.FILE_SEPARATOR
                + TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL + suffix).toPath();
    }

    private static Path pkIndex() {
        return collectionFile(
                Globals.INDEX_FILE_NAME_SEPARATOR + Globals.PK_INDEX_FILE_NAME + Globals.INDEX_FILE_EXTENSION);
    }

    private static long reconcileCountingReads(boolean expectedRewrite) throws Exception {
        final var target = pkIndex().toAbsolutePath();
        final var reads = new AtomicLong();
        try (var _ = mockStatic(Files.class, invocation -> {
            if (invocation.getMethod().getName().equals("readAllBytes")
                    && ((Path) invocation.getArgument(0)).toAbsolutePath().equals(target)) {
                reads.incrementAndGet();
            }
            return invocation.callRealMethod();
        })) {
            assertEquals(expectedRewrite, PageOccupancyReconciler.reconcile(TestGlobals.PROJECT, TestGlobals.COLL));
        }
        return reads.get();
    }

    @Test
    public void test_a_clean_reconcile_parses_the_pk_index_once() throws Exception {
        save("a");
        save("b");

        assertEquals(1L, reconcileCountingReads(false));
    }

    @Test
    public void test_an_adoption_parses_the_pk_index_again_and_counts_the_adopted_row() throws Exception {
        save("a");
        Files.writeString(collectionFile("-0.dat"), "{\"_id\":\"orphan\",\"payload\":\"x\"}\n", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);

        assertEquals(2L, reconcileCountingReads(true));

        final var ids = fs.readWholePkIndexFile(TestGlobals.PROJECT, TestGlobals.COLL).stream()
                .map(PkIndexEntry::getValue).sorted().toList();
        assertEquals(List.of("a", "orphan"), ids);
    }

    @Test
    public void test_a_retirement_parses_the_pk_index_again_before_adopting() throws Exception {
        save("a");
        save("b");
        final var kept = fs.readWholePkIndexFile(TestGlobals.PROJECT, TestGlobals.COLL).stream()
                .filter(row -> row.getValue().equals("a")).findFirst().orElseThrow();
        try (var page = new RandomAccessFile(collectionFile("-0.dat").toFile(), Globals.RW_PERMISSIONS)) {
            page.setLength(kept.getPosition() + kept.getLength());
        }

        final var reads = reconcileCountingReads(true);

        assertTrue(reads >= 2L, "the rows read before the retirement are stale once it rewrote pk.idx");
        assertFalse(fs.readWholePkIndexFile(TestGlobals.PROJECT, TestGlobals.COLL).stream()
                .anyMatch(row -> row.getValue().equals("b")));
    }
}
