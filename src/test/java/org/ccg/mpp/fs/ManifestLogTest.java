package org.ccg.mpp.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.ccg.mpp.data.FileEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ManifestLogTest {
    @TempDir
    Path folder;

    private Path manifestWith(String... lines) throws IOException {
        final var file = folder.resolve("manifest.log");
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return file;
    }

    private static FileManifest loaded(Path file) throws IOException {
        final var manifest = new FileManifest();
        ManifestLog.load(file, manifest);
        return manifest;
    }

    @Test
    public void test_the_last_line_for_a_path_wins_and_a_tombstone_hides_it() throws Exception {
        final var file = manifestWith(ManifestLog.incarnation(7), ManifestLog.directory("/a", 1),
                ManifestLog.file("/a/x", 2, 10), ManifestLog.file("/a/x", 3, 12), ManifestLog.file("/a/y", 4, 1),
                ManifestLog.tombstone("/a/y", 5));
        final var manifest = loaded(file);
        assertEquals(7, manifest.incarnation());
        assertEquals(FileEntry.file(3, 12), manifest.get("/a/x"));
        assertNull(manifest.get("/a/y"));
        assertEquals(5L, manifest.tombstone("/a/y"));
        assertEquals(12, manifest.bytes());
        assertEquals(2, manifest.entryCount());
        assertEquals(5, manifest.highestVersion());
    }

    @Test
    public void test_an_absent_manifest_loads_nothing() throws Exception {
        assertFalse(ManifestLog.load(folder.resolve("none.log"), new FileManifest()));
    }

    @Test
    public void test_appended_batches_are_read_back_whole() throws Exception {
        final var file = folder.resolve("manifest.log");
        Files.createFile(file);
        ManifestLog.append(file, List.of(ManifestLog.directory("/d", 1)));
        ManifestLog.append(file, List.of(ManifestLog.tombstone("/d/old", 2), ManifestLog.move("/d/old", "/d/new"),
                ManifestLog.file("/d/new", 2, 5)));
        final var manifest = loaded(file);
        assertEquals(FileEntry.file(2, 5), manifest.get("/d/new"));
        assertEquals(1, manifest.pendingMoves().size());
        assertEquals("/d/new", manifest.pendingMoves().getFirst().to());
        assertEquals(6, manifest.logLines());
    }

    @Test
    public void test_a_torn_last_line_is_dropped() throws Exception {
        final var file = manifestWith(ManifestLog.file("/a", 1, 1));
        Files.writeString(file, "F\u001f/b\u001f2", StandardOpenOption.APPEND);
        final var manifest = new FileManifest();
        assertTrue(ManifestLog.load(file, manifest));
        assertEquals(1, manifest.entryCount());
    }

    @Test
    public void test_an_incomplete_batch_at_the_tail_is_dropped() throws Exception {
        final var file = manifestWith(ManifestLog.file("/a", 1, 1), "B\u001f3", ManifestLog.file("/b", 2, 2));
        final var manifest = new FileManifest();
        assertTrue(ManifestLog.load(file, manifest));
        assertNull(manifest.get("/b"));
        assertEquals(FileEntry.file(1, 1), manifest.get("/a"));
    }

    @Test
    public void test_a_batch_whose_checksum_fails_at_the_tail_is_dropped() throws Exception {
        final var file = manifestWith(ManifestLog.file("/a", 1, 1), "B\u001f1", ManifestLog.file("/b", 2, 2),
                "E\u001fdeadbeef");
        assertTrue(ManifestLog.load(file, new FileManifest()));
    }

    @Test
    public void test_damage_before_the_last_record_is_reported_never_skipped() throws Exception {
        final var damagedLine = manifestWith(ManifestLog.file("/a", 1, 1), "garbage", ManifestLog.file("/b", 2, 2));
        assertThrows(IOException.class, () -> loaded(damagedLine));
        final var damagedBatch = manifestWith("B\u001f1", ManifestLog.file("/b", 2, 2), "E\u001fdeadbeef",
                ManifestLog.file("/c", 3, 3));
        assertThrows(IOException.class, () -> loaded(damagedBatch));
        final var badMember = manifestWith("B\u001f1", "Q\u001f/x", "E\u001f" + crcHex(List.of("Q\u001f/x")),
                ManifestLog.file("/c", 3, 3));
        assertThrows(IOException.class, () -> loaded(badMember));
        final var badCount = manifestWith("B\u001fx", ManifestLog.file("/c", 3, 3));
        assertThrows(IOException.class, () -> loaded(badCount));
    }

    private static String crcHex(List<String> records) {
        final var crc = new java.util.zip.CRC32();
        for (final var record : records) {
            crc.update(record.getBytes(StandardCharsets.UTF_8));
            crc.update('\n');
        }
        return Long.toHexString(crc.getValue());
    }

    @Test
    public void test_a_pending_replace_records_its_temp_file() throws Exception {
        final var manifest = loaded(manifestWith(ManifestLog.pending("/p", 9, 3, "9-1.part")));
        assertEquals(FileEntry.file(9, 3), manifest.get("/p"));
        assertEquals("9-1.part", manifest.pendingReplaces().get("/p").tmpName());
    }

    @Test
    public void test_a_failed_append_leaves_the_file_as_it_was() throws Exception {
        final var file = manifestWith(ManifestLog.file("/a", 1, 1));
        final var before = Files.readAllBytes(file);
        try (var forcer = RecordingForcer.install(true)) {
            forcer.failingFor("manifest.log");
            assertThrows(IOException.class, () -> ManifestLog.append(file, List.of(ManifestLog.file("/b", 2, 2))));
        }
        assertEquals(new String(before, StandardCharsets.UTF_8), Files.readString(file));
    }
}
