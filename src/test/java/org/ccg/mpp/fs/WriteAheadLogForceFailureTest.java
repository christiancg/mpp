package org.ccg.mpp.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.ccg.mpp.fault.FaultAction;
import org.ccg.mpp.fault.FaultInjector;
import org.ccg.mpp.fault.FaultPoint;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.test.RequiresDirectorySync;
import org.ccg.mpp.test.RequiresFileKeys;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@RequiresDirectorySync
@RequiresFileKeys
public class WriteAheadLogForceFailureTest {
    private static final String PROJECT = "forceFailProject";
    private static final String COLL = "forceFailColl";

    private final FaultInjector injector = IocContainer.get(FaultInjector.class);

    @TempDir
    File tmp;

    private PowerLossFixture power;
    private ReplacerFixture rf;
    private WriteAheadLog wal;

    @BeforeEach
    public void setUp() throws Exception {
        power = PowerLossFixture.install();
        rf = new ReplacerFixture(tmp, PROJECT, COLL);
        for (final var id : List.of("a", "b", "c")) {
            rf.appender.insert(rf.entry(id, "old-" + id, 0), ReplacerFixture.line(id, "old-" + id));
        }
        wal = new WriteAheadLog(rf.fx.paths);
        wal.checkpoint(PROJECT, COLL);
        TestUtils.setPrivateField(injector, "enabled", true);
    }

    @AfterEach
    public void tearDown() throws Exception {
        injector.releaseAll();
        TestUtils.setPrivateField(injector, "enabled", false);
        ForceFailures.forgetUnder(tmp);
        power.close();
    }

    private void failNextForceOf(File file) {
        injector.arm(FaultPoint.FILE_FORCED, FaultAction.FAIL, file.getName());
    }

    private Map<String, String> afterPowerLossAndRedo() throws IOException {
        power.powerLoss();
        PowerLossFixture.restart(rf.fx.paths, PROJECT, COLL);
        ForceFailures.forgetUnder(tmp);
        WalRedo.redoAll(rf.fx.paths);
        final var values = new TreeMap<String, String>();
        try (var entries = new DocumentPageStore(rf.fx.paths).streamEntries(PROJECT, COLL)) {
            entries.forEach(entry -> values.merge(entry.get_id(), entry.getData().get("v").asJsonString().getValue(),
                    (first, second) -> first + "," + second));
        }
        assertTrue(rf.fx.everyRowReadsItsOwnRecord(), "every pk row points at its own record");
        return values;
    }

    private static Map<String, String> withNewB() {
        return Map.of("a", "old-a", "b", "new-b", "c", "old-c");
    }

    private void aFailedCheckpointForceOf(File file) throws Exception {
        rf.replace("b", "new-b", 0, PagePlacement.APPEND);
        failNextForceOf(file);

        assertThrows(IOException.class, () -> wal.checkpoint(PROJECT, COLL));
        wal.checkpoint(PROJECT, COLL);

        assertEquals(withNewB(), afterPowerLossAndRedo());
    }

    @Test
    public void aFailedCheckpointForceOfAPageIsNeverRetriedAndTrusted() throws Exception {
        aFailedCheckpointForceOf(rf.fx.page(0));
    }

    @Test
    public void aFailedCheckpointForceOfThePkIndexIsNeverRetriedAndTrusted() throws Exception {
        aFailedCheckpointForceOf(rf.fx.paths.pkIndexFile(PROJECT, COLL));
    }

    @Test
    public void theRebuiltFileIsANewFileHoldingEveryLoggedByte() throws Exception {
        final var page = rf.fx.page(0);
        rf.replace("b", "new-b", 0, PagePlacement.APPEND);
        final var bytes = Files.readAllBytes(page.toPath());
        final var inode = Files.readAttributes(page.toPath(), BasicFileAttributes.class).fileKey();
        failNextForceOf(page);
        assertThrows(IOException.class, () -> wal.checkpoint(PROJECT, COLL));
        assertTrue(ForceFailures.hasFailed(page.toPath()));

        wal.checkpoint(PROJECT, COLL);

        assertNotEquals(inode, Files.readAttributes(page.toPath(), BasicFileAttributes.class).fileKey());
        assertArrayEquals(bytes, Files.readAllBytes(page.toPath()));
        assertFalse(ForceFailures.hasFailed(page.toPath()));
        assertFalse(rf.fx.paths.collectionFolder(PROJECT, COLL).toPath().resolve(page.getName() + ".repair").toFile()
                .exists());
    }

    @Test
    public void aFailedRebuildKeepsTheLogRecords() throws Exception {
        final var page = rf.fx.page(0);
        rf.replace("b", "new-b", 0, PagePlacement.APPEND);
        failNextForceOf(page);
        assertThrows(IOException.class, () -> wal.checkpoint(PROJECT, COLL));
        injector.arm(FaultPoint.FILE_FORCED, FaultAction.FAIL, page.getName() + ".repair");

        assertThrows(IOException.class, () -> wal.checkpoint(PROJECT, COLL));

        assertTrue(ForceFailures.hasFailed(page.toPath()));
        assertEquals(withNewB(), afterPowerLossAndRedo());
    }

    @Test
    public void aFailedForceOfAFileThatIsGoneIsForgotten() throws Exception {
        final var gone = rf.fx.page(7).toPath();
        ForceFailures.failed(gone);

        wal.checkpoint(PROJECT, COLL);

        assertFalse(ForceFailures.hasFailed(gone));
    }

    @Test
    public void aFailedFieldIndexLogForceIsRebuiltFromItsLiveCopy() throws Exception {
        final var indexLog = rf.fx.paths.collectionFolder(PROJECT, COLL).toPath().resolve(COLL + "-v-String.idxlog");
        Files.writeString(indexLog, "logged index changes\n");
        final var inode = Files.readAttributes(indexLog, BasicFileAttributes.class).fileKey();
        ForceFailures.failed(indexLog);
        rf.replace("b", "new-b", 0, PagePlacement.APPEND);

        wal.checkpoint(PROJECT, COLL);

        assertNotEquals(inode, Files.readAttributes(indexLog, BasicFileAttributes.class).fileKey());
        assertEquals("logged index changes\n", Files.readString(indexLog));
        assertFalse(ForceFailures.hasFailed(indexLog));
    }

    @Test
    public void aWriteAfterAFailedLogForceIsNeverForcedIntoTheSameLog() throws Exception {
        failNextForceOf(rf.fx.paths.writeAheadLog(PROJECT, COLL));
        assertThrows(IOException.class, () -> rf.replace("b", "new-b", 0, PagePlacement.APPEND));

        rf.replace("c", "new-c", 0, PagePlacement.APPEND);

        assertEquals(Map.of("a", "old-a", "b", "old-b", "c", "new-c"), afterPowerLossAndRedo());
    }
}
