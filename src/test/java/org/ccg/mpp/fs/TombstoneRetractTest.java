package org.ccg.mpp.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.ccg.mpp.config.Globals;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class TombstoneRetractTest {
    private static final String PROJECT = "retractProject";
    private static final String COLL = "retractColl";

    @TempDir
    File tmp;

    private StorageFixture fx;
    private TombstoneStore store;
    private File file;

    @BeforeEach
    public void setUp() throws IOException {
        fx = new StorageFixture(tmp, PROJECT, COLL);
        store = new TombstoneStore(fx.paths);
        file = fx.paths.tombstoneFile(PROJECT, COLL);
    }

    @AfterEach
    public void tearDown() {
        assertTrue(file.setWritable(true) || !file.exists());
        TombstoneStore.forgetUnder(fx.paths.collectionFolder(PROJECT, COLL));
    }

    private List<String> lines() throws IOException {
        return Files.readAllLines(file.toPath());
    }

    private void forgetResidents() {
        TombstoneStore.forgetUnder(fx.paths.collectionFolder(PROJECT, COLL));
    }

    private List<String> seen(TombstoneView view) {
        final var seen = new ArrayList<String>();
        view.forEach((id, version) -> seen.add(id + "@" + version));
        return seen;
    }

    @Test
    public void aRetractAppendsALineAndHidesOnlyThatVersion() throws IOException {
        store.appendAll(PROJECT, COLL, List.of("x", "x"), List.of(5L, 9L));

        store.retract(PROJECT, COLL, "x", 9L);

        assertEquals(5L, store.versionOf(PROJECT, COLL, "x"));
        assertTrue(lines().contains("x" + Globals.ID_SEPARATOR + "-9"), lines().toString());
        assertEquals(3, lines().size(), "the retract is appended, never a rewrite");
    }

    @Test
    public void aRetractLineSurvivesARestartParse() throws IOException {
        store.appendAll(PROJECT, COLL, List.of("x", "x"), List.of(5L, 9L));
        store.retract(PROJECT, COLL, "x", 9L);
        forgetResidents();

        assertEquals(5L, store.versionOf(PROJECT, COLL, "x"));
        assertEquals(5L, TombstoneStore.read(file).get("x"), "the healing read pairs the retract off too");
    }

    @Test
    public void compactionDropsRetractedPairs() throws IOException {
        store.appendAll(PROJECT, COLL, List.of("x", "x", "y"), List.of(5L, 9L, 4L));
        store.retract(PROJECT, COLL, "x", 9L);

        store.compact(PROJECT, COLL, 0L);

        assertEquals(List.of("x" + Globals.ID_SEPARATOR + "5", "y" + Globals.ID_SEPARATOR + "4"),
                lines().stream().sorted().toList());
    }

    @Test
    public void aRetractThatCannotAppendIsHeldBackFromReadsAndTheView() throws IOException {
        store.appendAll(PROJECT, COLL, List.of("y", "z"), List.of(7L, 3L));
        assertTrue(file.setWritable(false));

        store.retract(PROJECT, COLL, "y", 7L);

        assertEquals(0L, store.versionOf(PROJECT, COLL, "y"));
        assertFalse(store.read(PROJECT, COLL).containsKey("y"));
        final var view = store.view(PROJECT, COLL);
        assertEquals(List.of("z@3"), seen(view));
        assertEquals(1, view.size());
        assertEquals(0L, view.versionOf("y"));
        assertEquals(2, lines().size(), "nothing reached the file");
    }

    @Test
    public void thePendingRetractLandsOnTheNextRead() throws IOException {
        store.appendAll(PROJECT, COLL, List.of("y"), List.of(7L));
        assertTrue(file.setWritable(false));
        store.retract(PROJECT, COLL, "y", 7L);
        assertTrue(file.setWritable(true));

        store.view(PROJECT, COLL);
        forgetResidents();

        assertTrue(lines().contains("y" + Globals.ID_SEPARATOR + "-7"), lines().toString());
        assertEquals(0L, store.versionOf(PROJECT, COLL, "y"));
    }

    @Test
    public void theNextTombstoneAppendRetriesAPendingRetractFirst() throws IOException {
        store.appendAll(PROJECT, COLL, List.of("y"), List.of(7L));
        assertTrue(file.setWritable(false));
        store.retract(PROJECT, COLL, "y", 7L);
        assertTrue(file.setWritable(true));

        store.append(PROJECT, COLL, "w", 11L);

        assertTrue(lines().contains("y" + Globals.ID_SEPARATOR + "-7"), lines().toString());
        assertEquals(11L, store.versionOf(PROJECT, COLL, "w"));
    }

    @Test
    public void aDropForgetsPendingRetracts() throws IOException {
        store.appendAll(PROJECT, COLL, List.of("y"), List.of(7L));
        assertTrue(file.setWritable(false));
        store.retract(PROJECT, COLL, "y", 7L);
        assertTrue(file.setWritable(true));

        forgetResidents();

        assertEquals(7L, store.versionOf(PROJECT, COLL, "y"), "a dropped collection keeps nothing held back");
        assertEquals(1, lines().size());
    }

    @Test
    public void aFileWithoutRetractsParsesUnchanged() throws IOException {
        Files.createDirectories(fx.paths.collectionFolder(PROJECT, COLL).toPath());
        Files.writeString(file.toPath(),
                "a" + Globals.ID_SEPARATOR + "3\nb" + Globals.ID_SEPARATOR + "4\na" + Globals.ID_SEPARATOR + "5\n");

        assertEquals(5L, store.versionOf(PROJECT, COLL, "a"));
        assertEquals(4L, store.versionOf(PROJECT, COLL, "b"));
        assertEquals(5L, TombstoneStore.read(file).get("a"));
    }

    @Test
    public void aRetractOfAVersionWithNoLineChangesNothing() throws IOException {
        store.appendAll(PROJECT, COLL, List.of("x"), List.of(5L));

        store.retract(PROJECT, COLL, "x", 6L);

        assertEquals(5L, store.versionOf(PROJECT, COLL, "x"));
    }
}
