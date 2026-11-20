package org.ccg.mpp.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import org.ccg.mpp.data.DbEntry;
import org.ccg.mpp.data.PkIndexEntry;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class PageVacuumReplacementFailureTest {
    private static final String PROJECT = "replProject";
    private static final String COLL = "replColl";

    @TempDir
    File tmp;

    private StorageFixture fx;
    private PageVacuum vacuum;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        fx = new StorageFixture(tmp, PROJECT, COLL);
        fx.seed(0, "a", "b", "c");
        final var failing = spy(fx.store);
        doThrow(new IOException("No space left on device")).when(failing).appendReplacements(anyString(), anyString(),
                any());
        vacuum = new PageVacuum(fx.paths, failing, fx.journal);
        vacuum.onVacuum(_ -> {
        });
        blankRecordOfA();
    }

    @AfterEach
    public void tearDown() throws Exception {
        DeferredApplies.clear(PROJECT, COLL);
        PkIndexStore.forgetUnder(fx.paths.collectionFolder(PROJECT, COLL));
        TestUtils.standardTearDown();
    }

    private void blankRecordOfA() throws IOException {
        final var row = fx.indexed("a");
        final var bytes = Files.readAllBytes(fx.page(0).toPath());
        for (var i = 0; i < row.getLength() - 1; i++) {
            bytes[(int) row.getPosition() + i] = ' ';
        }
        Files.write(fx.page(0).toPath(), bytes);
        PageLengths.forget(fx.page(0));
    }

    private List<PkIndexEntry> liveRows() throws IOException {
        return fx.indexedRows().stream().filter(row -> !row.getValue().equals("a")).toList();
    }

    @Test
    public void aBulkDeleteAfterAFailedAppendReplacementsBlanksNothingElse() throws Exception {
        assertThrows(IOException.class, () -> vacuum.vacuum(PROJECT, COLL, 0, liveRows()));

        final var appender = new PageAppender(fx.paths, fx.store, vacuum);
        try {
            final var pointScanned = fx.store.rowsFor(PROJECT, COLL, Set.of("b")).get("b");
            appender.bulkInsertAndDelete(PROJECT, COLL, List.of(), DbEntry::getPage, DbEntry::encodeLine,
                    List.of(pointScanned));
        } catch (IOException refused) {
            assertTrue(refused.getMessage().contains(PROJECT + "|" + COLL), refused.getMessage());
        }

        final var text = Files.readString(fx.page(0).toPath(), StandardCharsets.UTF_8);
        assertTrue(text.contains(StorageFixture.record("c", "old-c")),
                "a live record survives a delete of another: " + text);
        assertEquals(1, fx.openIntents());
        assertThrows(IOException.class, () -> fx.store.readWholePkIndexFile(PROJECT, COLL));
    }

    @Test
    public void theNextUseRederivesTheRowsOnceTheReplacementCanBeRecorded() throws Exception {
        assertThrows(IOException.class, () -> vacuum.vacuum(PROJECT, COLL, 0, liveRows()));
        assertTrue(DeferredApplies.pending(PROJECT + "|" + COLL), "the collection is flagged for its next use");

        assertTrue(new WriteIntentRecovery(fx.paths, fx.store, fx.journal).recoverBehindAVacuum(PROJECT, COLL));

        assertEquals(0, fx.openIntents());
        final var rows = fx.store.rowsFor(PROJECT, COLL, Set.of("b", "c"));
        assertEquals(StorageFixture.record("b", "old-b"), fx.readAt(rows.get("b")));
        assertEquals(StorageFixture.record("c", "old-c"), fx.readAt(rows.get("c")));
    }

    @Test
    public void aRecoveryThatStillCannotRecordKeepsThePkIndexBehind() throws Exception {
        assertThrows(IOException.class, () -> vacuum.vacuum(PROJECT, COLL, 0, liveRows()));
        final var stillFailing = spy(fx.store);
        doThrow(new IOException("No space left on device")).when(stillFailing).appendReplacements(anyString(),
                anyString(), any());

        assertThrows(IOException.class,
                () -> new WriteIntentRecovery(fx.paths, stillFailing, fx.journal).recoverBehindAVacuum(PROJECT, COLL));

        assertThrows(IOException.class, () -> fx.store.readWholePkIndexFile(PROJECT, COLL));
        assertTrue(DeferredApplies.pending(PROJECT + "|" + COLL));
        assertEquals(1, fx.openIntents());
    }

    @Test
    public void aDroppedFolderForgetsThatItsPkIndexWasBehind() throws Exception {
        assertThrows(IOException.class, () -> vacuum.vacuum(PROJECT, COLL, 0, liveRows()));

        PkIndexStore.forgetUnder(fx.paths.collectionFolder(PROJECT, COLL));

        assertEquals(3, fx.store.readWholePkIndexFile(PROJECT, COLL).size());
    }
}
