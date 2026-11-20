package org.ccg.mpp.fs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.fs.io.OwnerOnlyFiles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class WriteIntentJournalTest {
    private static final String PROJECT = "intentProject";
    private static final String COLL = "intent-coll";

    @TempDir
    File tmp;

    private FilePaths paths;
    private WriteIntentJournal journal;

    @BeforeEach
    public void setUp() {
        paths = new FilePaths();
        paths.useDbPath(tmp.getAbsolutePath());
        assertTrue(paths.collectionFolder(PROJECT, COLL).mkdirs());
        journal = new WriteIntentJournal(paths);
    }

    private static List<WriteIntent> everyKind() {
        return List.of(new WriteIntent.Vacuum(3), new WriteIntent.Vacuum(7));
    }

    private File log() {
        return paths.intentLog(PROJECT, COLL);
    }

    private IntentLogCodec.Parsed parsed(byte[] bytes) {
        return IntentLogCodec.parse(log(), bytes);
    }

    private List<Long> openSeqs() throws IOException {
        return journal.openIntents(PROJECT, COLL).stream().map(WriteIntentJournal.Intent::seq).toList();
    }

    @Test
    public void anIntentRoundTripsEveryPage() throws IOException {
        final var intent = journal.begin(PROJECT, COLL, everyKind());

        final var open = journal.openIntents(PROJECT, COLL);

        assertEquals(1, open.size());
        assertEquals(intent, open.getFirst());
        assertEquals(PROJECT, open.getFirst().projectName());
        assertEquals(COLL, open.getFirst().collName());
        assertEquals(everyKind(), open.getFirst().items());
    }

    @Test
    public void aLogCutAnywhereInsideItsOnlyBlockHasNoOpenIntent() throws IOException {
        journal.begin(PROJECT, COLL, everyKind());
        final var bytes = Files.readAllBytes(log().toPath());

        for (var cut = 0; cut < bytes.length; cut++) {
            final var parsed = parsed(Arrays.copyOf(bytes, cut));
            assertTrue(parsed.open().isEmpty(), "cut at " + cut);
            assertEquals(0L, parsed.cleanLength(), "cut at " + cut);
        }
        assertEquals(bytes.length, parsed(bytes).cleanLength());
    }

    @Test
    public void aCountThatDisagreesWithTheItemsIsTorn() {
        final var text = String.join(Globals.ID_SEPARATOR, "INTENT", "1", PROJECT, COLL, "2") + "\n"
                + String.join(Globals.ID_SEPARATOR, "VACUUM", "1") + "\nEND\n";

        assertTrue(parsed(text.getBytes(StandardCharsets.UTF_8)).open().isEmpty());
    }

    @Test
    public void anUnknownItemOrAMangledFieldIsTorn() {
        for (final var item : List.of("MOVE" + Globals.ID_SEPARATOR + "1", "VACUUM" + Globals.ID_SEPARATOR + "x",
                "DELETE" + Globals.ID_SEPARATOR + "id")) {
            final var text = String.join(Globals.ID_SEPARATOR, "INTENT", "1", PROJECT, COLL, "1") + "\n" + item
                    + "\nEND\n";
            assertTrue(parsed(text.getBytes(StandardCharsets.UTF_8)).open().isEmpty(), item);
        }
    }

    @Test
    public void test_begin_end_truncates_when_idle() throws IOException {
        final var intent = journal.begin(PROJECT, COLL, everyKind());
        assertTrue(log().length() > 0);

        journal.end(intent);

        assertEquals(0L, log().length());
        assertTrue(openSeqs().isEmpty());
    }

    @Test
    public void test_open_intent_is_recovered() throws IOException {
        final var first = journal.begin(PROJECT, COLL, List.of(new WriteIntent.Vacuum(1)));
        final var second = journal.begin(PROJECT, COLL, List.of(new WriteIntent.Vacuum(2)));

        journal.end(first);

        assertEquals(List.of(second.seq()), openSeqs());
        assertTrue(log().length() > 0, "an open intent keeps the log");
    }

    @Test
    public void test_torn_last_block_is_discarded() throws IOException {
        final var kept = journal.begin(PROJECT, COLL, List.of(new WriteIntent.Vacuum(1)));
        final var keptLength = log().length();
        journal.begin(PROJECT, COLL, List.of(new WriteIntent.Vacuum(2)));
        final var bytes = Files.readAllBytes(log().toPath());
        Files.write(log().toPath(), Arrays.copyOf(bytes, bytes.length - 2));

        assertEquals(List.of(kept.seq()), openSeqs());
        assertEquals(keptLength, log().length(), "the torn block is cut before anything appends after it");
    }

    @Test
    public void test_lost_done_is_settled_again() throws IOException {
        final var intent = journal.begin(PROJECT, COLL, everyKind());
        final var withoutDone = Files.readAllBytes(log().toPath());
        journal.end(intent);
        Files.write(log().toPath(), withoutDone);

        assertEquals(List.of(intent.seq()), openSeqs());
        journal.end(journal.openIntents(PROJECT, COLL).getFirst());
        assertEquals(0L, log().length());
    }

    @Test
    public void test_two_open_intents_recover_in_order() throws IOException {
        final var first = journal.begin(PROJECT, COLL, List.of(new WriteIntent.Vacuum(9)));
        final var second = journal.begin(PROJECT, COLL, List.of(new WriteIntent.Vacuum(1)));

        assertEquals(List.of(first.seq(), second.seq()), openSeqs());
        assertTrue(first.seq() < second.seq());
        assertEquals(List.of(log()), journal.findLogs());
    }

    @Test
    public void anIntentOpenedByAnotherJournalSurvivesTheFirstJournalGoingIdle() throws IOException {
        final var mine = journal.begin(PROJECT, COLL, List.of(new WriteIntent.Vacuum(1)));
        final var theirs = new WriteIntentJournal(paths).begin(PROJECT, COLL, List.of(new WriteIntent.Vacuum(2)));

        journal.end(mine);

        assertEquals(List.of(theirs.seq()), openSeqs());
    }

    @Test
    public void anIntentAppendedBehindTheJournalsBackSurvivesItGoingIdle() throws IOException {
        final var mine = journal.begin(PROJECT, COLL, List.of(new WriteIntent.Vacuum(1)));
        final var planted = new WriteIntentJournal.Intent(log(), 99L, PROJECT, COLL,
                List.of(new WriteIntent.Vacuum(2)));
        Files.write(log().toPath(), IntentLogCodec.encode(planted), StandardOpenOption.APPEND);

        journal.end(mine);

        assertEquals(List.of(99L), openSeqs());
    }

    @Test
    public void aStuckIntentIsKeptWhenTheLogIsCompacted() throws IOException {
        final var stuck = journal.begin(PROJECT, COLL, everyKind());
        var longest = log().length();
        while (log().length() >= longest) {
            longest = log().length();
            journal.end(journal.begin(PROJECT, COLL, List.of(new WriteIntent.Vacuum(4))));
        }

        assertTrue(longest > WriteIntentJournal.COMPACTION_BYTES / 2, "the log grew until it was compacted");
        assertEquals(List.of(stuck.seq()), openSeqs());
        assertEquals(IntentLogCodec.encode(stuck).length, log().length());
    }

    @Test
    public void anEndThatCannotWriteLeavesTheIntentOpen() throws IOException {
        final var intent = journal.begin(PROJECT, COLL, everyKind());
        assertTrue(log().setWritable(false));
        try {
            assertDoesNotThrow(() -> journal.end(intent));
        } finally {
            assertTrue(log().setWritable(true));
        }

        assertEquals(List.of(intent.seq()), openSeqs());
    }

    @Test
    public void aDroppedCollectionsBookkeepingIsForgotten() throws IOException {
        journal.begin(PROJECT, COLL, List.of(new WriteIntent.Vacuum(1)));
        journal.begin(PROJECT, COLL, List.of(new WriteIntent.Vacuum(2)));
        final var collection = paths.collectionFolder(PROJECT, COLL);

        assertTrue(FileTreeDeleter.delete(collection));
        assertTrue(collection.mkdirs());

        assertEquals(1L, journal.begin(PROJECT, COLL, List.of(new WriteIntent.Vacuum(3))).seq(),
                "a recreated collection starts a fresh log, not the dropped one's sequence");
    }

    @Test
    public void theLogIsCreatedOwnerOnly() throws IOException {
        assumeTrue(OwnerOnlyFiles.supported(), "only a POSIX file system has owner-only permission bits");
        journal.begin(PROJECT, COLL, everyKind());

        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(log().toPath()));
    }

    @Test
    public void aMissingDataFolderHasNoLogs() throws IOException {
        paths.useDbPath(new File(tmp, "absent").getAbsolutePath());

        assertTrue(journal.findLogs().isEmpty());
        assertTrue(journal.openIntents(PROJECT, COLL).isEmpty());
        assertFalse(log().exists());
    }
}
