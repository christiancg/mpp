package org.ccg.mpp.unit.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.ccg.mpp.unit.structure.JavaSources.JavaSourceFile;
import org.junit.jupiter.api.Test;

public class FileIoBoundaryTest {
    private static final String FS = "org.ccg.mpp.fs";
    private static final String FS_IO = "org.ccg.mpp.fs.io";
    private static final Pattern FILE_ACCESS = Pattern.compile("\\b(java\\.nio\\.file\\.Files|Files\\.|"
            + "java\\.io\\.(File|FileInputStream|FileOutputStream|FileReader|FileWriter|RandomAccessFile)\\b|"
            + "FileChannel|new File\\()");
    private static final Pattern PROJECT_REFERENCE = Pattern.compile("\\borg\\.ccg\\.mpp\\.(?!fs\\.io\\b)[\\w.]+");

    static Set<String> fileAccessOutsideFs(List<JavaSourceFile> files) {
        final var found = new TreeSet<String>();
        for (final var file : files) {
            if (!file.isInPackage(FS)) {
                final var matcher = FILE_ACCESS.matcher(file.text().code());
                while (matcher.find()) {
                    found.add(file.relativePath() + " -> " + matcher.group());
                }
            }
        }
        return found;
    }

    static Set<String> fsIoReachesNoProjectPackage(List<JavaSourceFile> files) {
        final var found = new TreeSet<String>();
        for (final var file : files) {
            if (file.isInPackage(FS_IO)) {
                final var matcher = PROJECT_REFERENCE.matcher(file.text().code());
                while (matcher.find()) {
                    found.add(file.relativePath() + " -> " + matcher.group());
                }
            }
        }
        return found;
    }

    @Test
    public void test_every_file_read_and_write_happens_in_fs() {
        final var found = fileAccessOutsideFs(JavaSources.under(JavaSources.MAIN));
        assertTrue(found.isEmpty(), "only org.ccg.mpp.fs may touch the filesystem; route it through fs.io: " + found);
    }

    @Test
    public void test_fs_io_imports_nothing_from_the_project() {
        final var found = fsIoReachesNoProjectPackage(JavaSources.under(JavaSources.MAIN));
        assertTrue(found.isEmpty(), "fs.io is a leaf and may use only java.*: " + found);
    }

    @Test
    public void test_a_synthetic_class_outside_fs_is_caught_on_every_branch() {
        final var source = JavaSources.parse("Config.java", """
                package org.ccg.mpp.config;
                import java.nio.file.Files;
                import java.io.File;
                import java.io.FileInputStream;
                import java.io.FileOutputStream;
                import java.io.FileReader;
                import java.io.FileWriter;
                import java.io.RandomAccessFile;
                import java.nio.channels.FileChannel;
                class Config {
                    Object a = Files.exists(null);
                    Object b = new File("x");
                }
                """);
        assertEquals(Set.of("Config.java -> java.nio.file.Files", "Config.java -> Files.",
                "Config.java -> java.io.File", "Config.java -> java.io.FileInputStream",
                "Config.java -> java.io.FileOutputStream", "Config.java -> java.io.FileReader",
                "Config.java -> java.io.FileWriter", "Config.java -> java.io.RandomAccessFile",
                "Config.java -> FileChannel", "Config.java -> new File("), fileAccessOutsideFs(List.of(source)));
    }

    @Test
    public void test_values_comments_and_fs_itself_are_not_caught() {
        final var values = JavaSources.parse("Paths.java", """
                package org.ccg.mpp.config;
                import java.nio.file.Path;
                import java.nio.file.FileSystems;
                import org.ccg.mpp.fs.io.OwnerOnlyFiles;
                class Paths {
                    // Files.exists is not called here
                    Object a = Path.of("x");
                    Object b = FileSystems.getDefault().getSeparator();
                    Object c = OwnerOnlyFiles.supported();
                }
                """);
        final var fs = JavaSources.parse("Store.java", """
                package org.ccg.mpp.fs;
                import java.nio.file.Files;
                class Store { Object a = Files.exists(null); }
                """);
        assertEquals(Set.of(), fileAccessOutsideFs(List.of(values, fs)));
    }

    @Test
    public void test_a_synthetic_fs_io_class_reaching_the_project_is_caught() {
        final var leaf = JavaSources.parse("Leaf.java", """
                package org.ccg.mpp.fs.io;
                import org.ccg.mpp.log.Logger;
                import org.ccg.mpp.fs.io.OwnerOnlyFiles.Creation;
                class Leaf { org.ccg.mpp.config.Configuration c; }
                """);
        assertEquals(Set.of("Leaf.java -> org.ccg.mpp.log.Logger", "Leaf.java -> org.ccg.mpp.config.Configuration"),
                fsIoReachesNoProjectPackage(List.of(leaf)));
    }
}
