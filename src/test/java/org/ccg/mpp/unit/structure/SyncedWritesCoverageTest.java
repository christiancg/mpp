package org.ccg.mpp.unit.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.ccg.mpp.unit.structure.JavaSources.JavaSourceFile;
import org.junit.jupiter.api.Test;

public class SyncedWritesCoverageTest {
    private static final String FS = "org.ccg.mpp.fs";
    private static final String FS_IO = "org.ccg.mpp.fs.io";
    private static final String HELPER = "/SyncedWrites.java";
    private static final Set<String> EXEMPT_WRITERS = Set.of("/WalPlan.java", "/WalRedo.java", "/WalWriteGroup.java",
            "/DurableShadow.java", "/SnapshotImageFile.java");
    private static final Pattern WRITE = Pattern.compile("new (java\\.io\\.)?FileOutputStream\\("
            + "|FileChannel\\.open\\([^;]*WRITE|Files\\.write|Files\\.newOutputStream|Files\\.move\\("
            + "|new (java\\.io\\.)?RandomAccessFile\\([^;]*RW_PERMISSIONS|OwnerOnlyFiles\\.create|\\.renameTo\\(");

    static Set<String> unsyncedWriters(List<JavaSourceFile> files) {
        final var found = new TreeSet<String>();
        for (final var file : files) {
            if (!file.isInPackage(FS) || file.isInPackage(FS_IO) || file.relativePath().endsWith(HELPER)
                    || EXEMPT_WRITERS.stream().anyMatch(file.relativePath()::endsWith)) {
                continue;
            }
            final var code = file.text().code();
            if (WRITE.matcher(code).find() && !code.contains("SyncedWrites.") && !code.contains("ForceGroup")) {
                found.add(file.relativePath());
            }
        }
        return found;
    }

    @Test
    public void test_every_fs_write_path_goes_through_synced_writes() {
        final var found = unsyncedWriters(JavaSources.under(JavaSources.MAIN));
        assertTrue(found.isEmpty(), "these fs classes write, create or move a file without calling SyncedWrites, so"
                + " syncWrites=true would acknowledge a write the device may not hold: " + found);
    }

    @Test
    public void test_a_synthetic_unsynced_writer_is_caught_on_every_branch() {
        final var branches = List.of("new java.io.FileOutputStream(f, true)",
                "java.nio.channels.FileChannel.open(p, java.nio.file.StandardOpenOption.WRITE)",
                "java.nio.file.Files.writeString(p, s)", "java.nio.file.Files.newOutputStream(p)",
                "java.nio.file.Files.move(p, q)", "new java.io.RandomAccessFile(f, Globals.RW_PERMISSIONS)",
                "OwnerOnlyFiles.createDirectory(p)", "f.renameTo(g)");
        for (final var branch : branches) {
            final var source = JavaSources.parse("org/ccg/mpp/fs/Writer.java", """
                    package org.ccg.mpp.fs;
                    class Writer {
                        Object write() throws Exception {
                            return %s;
                        }
                    }
                    """.formatted(branch));
            assertEquals(Set.of("org/ccg/mpp/fs/Writer.java"), unsyncedWriters(List.of(source)), branch);
        }
    }

    @Test
    public void test_a_reader_and_a_synced_writer_pass() {
        final var reader = JavaSources.parse("org/ccg/mpp/fs/Reader.java", """
                package org.ccg.mpp.fs;
                class Reader {
                    Object read() throws Exception {
                        return new java.io.RandomAccessFile(f, Globals.R_PERMISSIONS);
                    }
                }
                """);
        final var synced = JavaSources.parse("org/ccg/mpp/fs/Synced.java", """
                package org.ccg.mpp.fs;
                class Synced {
                    void write() throws Exception {
                        try (var out = new java.io.FileOutputStream(f, true)) {
                            SyncedWrites.content(out.getChannel(), p);
                        }
                    }
                }
                """);

        assertEquals(Set.of(), unsyncedWriters(List.of(reader, synced)));
    }
}
