package io.github.christiancg.mpp.client.it;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.christiancg.mpp.client.error.ConflictException;
import io.github.christiancg.mpp.client.error.NotFoundException;
import io.github.christiancg.mpp.client.files.FileInfo;
import io.github.christiancg.mpp.client.files.FileKind;
import io.github.christiancg.mpp.client.files.ListOptions;
import io.github.christiancg.mpp.client.files.ProjectFiles;
import io.github.christiancg.mpp.client.files.WriteMode;
import io.github.christiancg.mpp.client.files.WriteOptions;
import io.github.christiancg.mpp.client.protocol.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class FilesIT extends ServerIT {
    private ProjectFiles files;

    @BeforeAll
    public void createProject() {
        final var project = client.project("files_it");
        project.create();
        files = project.files();
    }

    private List<String> paths(Iterable<FileInfo> entries) {
        final var paths = new ArrayList<String>();
        entries.forEach(entry -> paths.add(entry.path()));
        return paths;
    }

    @Test
    public void test_binary_and_text_content_round_trip() {
        final var everyByte = new byte[256];
        for (var i = 0; i < everyByte.length; i++) {
            everyByte[i] = (byte) i;
        }
        final var written = files.write("/bin/all.bin", everyByte);
        assertEquals("/bin/all.bin", written.path());
        assertEquals(256, written.size());
        assertArrayEquals(everyByte, files.read("bin/all.bin"));
        files.writeText("/text/hello.txt", "hola, ñandú ✓\n");
        assertEquals("hola, ñandú ✓\n", files.readText("/text/hello.txt"));
        final var range = files.readRange("/bin/all.bin", 250, 100);
        assertEquals(6, range.length());
        assertEquals(250, range.offset());
        assertEquals(256, range.size());
        assertTrue(range.eof());
        final var past = files.readRange("/bin/all.bin", 1000, 10);
        assertEquals(0, past.length());
        assertTrue(past.eof());
        assertEquals("hola", files.readTextRange("/text/hello.txt", 0, 4).text());
    }

    @Test
    public void test_append_adds_to_the_end_and_conditional_writes_check_the_version() {
        final var first = files.append("/logs/app.log", "one\n".getBytes(StandardCharsets.UTF_8));
        final var second = files.append("/logs/app.log", "two\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(8, second.size());
        assertNotEquals(first.version(), second.version());
        assertEquals("one\ntwo\n", files.readText("/logs/app.log"));
        final var stale = assertThrows(ConflictException.class,
                () -> files.writeText("/logs/app.log", "x", WriteOptions.builder().ifVersion(first.version()).build()));
        assertTrue(stale.hasCode(ErrorCode.FILE_VERSION_CONFLICT));
        files.writeText("/logs/app.log", "three\n",
                WriteOptions.builder().mode(WriteMode.APPEND).ifVersion(second.version()).build());
        assertEquals("one\ntwo\nthree\n", files.readText("/logs/app.log"));
        final var exists = assertThrows(ConflictException.class,
                () -> files.writeText("/logs/app.log", "x", WriteOptions.builder().mode(WriteMode.CREATE).build()));
        assertTrue(exists.hasCode(ErrorCode.FILE_EXISTS));
        files.writeText("/logs/new.log", "fresh", WriteOptions.builder().ifAbsent().build());
        assertEquals("fresh", files.readText("/logs/new.log"));
    }

    @Test
    public void test_stat_describes_files_and_folders_and_a_missing_path_is_not_found() {
        final var written = files.writeText("/stat/a.txt", "abc");
        final var file = files.stat("/stat/a.txt");
        assertEquals(FileKind.FILE, file.kind());
        assertEquals(3, file.size());
        assertEquals(written.version(), file.version());
        assertTrue(file.modifiedAt().toEpochMilli() > 0);
        assertEquals(FileKind.DIRECTORY, files.stat("/stat").kind());
        final var missing = assertThrows(NotFoundException.class, () -> files.stat("/stat/none.txt"));
        assertTrue(missing.hasCode(ErrorCode.FILE_NOT_FOUND));
        assertThrows(NotFoundException.class, () -> files.read("/stat/none.txt"));
    }

    @Test
    public void test_list_pages_through_a_folder_and_its_subtree() {
        for (var i = 0; i < 5; i++) {
            files.writeText("/list/f" + i + ".txt", "x");
        }
        files.writeText("/list/sub/deep.txt", "y");
        final var pagesOfTwo = ListOptions.builder().pageSize(2).build();
        assertEquals(
                List.of("/list/f0.txt", "/list/f1.txt", "/list/f2.txt", "/list/f3.txt", "/list/f4.txt", "/list/sub"),
                paths(files.list("/list", pagesOfTwo)));
        final var subtree = paths(files.list("/list", ListOptions.builder().recursive(true).pageSize(3).build()));
        assertEquals(7, subtree.size());
        assertTrue(subtree.contains("/list/sub/deep.txt"));
        assertEquals(List.of("/list/f3.txt", "/list/f4.txt", "/list/sub"),
                paths(files.list("/list", ListOptions.builder().startAfter("/list/f2.txt").build())));
    }

    @Test
    public void test_move_renames_and_overwrites_only_when_asked() {
        files.writeText("/move/a.txt", "a");
        files.writeText("/move/b.txt", "b");
        final var refused = assertThrows(ConflictException.class, () -> files.move("/move/a.txt", "/move/b.txt"));
        assertTrue(refused.hasCode(ErrorCode.FILE_EXISTS));
        final var moved = files.move("/move/a.txt", "/move/b.txt", true);
        assertEquals("/move/b.txt", moved.path());
        assertEquals("a", files.readText("/move/b.txt"));
        files.move("/move", "/moved/into/here");
        assertEquals("a", files.readText("/moved/into/here/b.txt"));
        assertThrows(NotFoundException.class, () -> files.stat("/move"));
    }

    @Test
    public void test_delete_needs_recursive_for_a_folder_with_content() {
        final var made = files.makeDirectory("/del/a/b");
        assertEquals("/del/a/b", made.path());
        assertEquals(3, made.created());
        files.writeText("/del/a/b/c.txt", "c");
        final var notEmpty = assertThrows(ConflictException.class, () -> files.delete("/del"));
        assertTrue(notEmpty.hasCode(ErrorCode.DIRECTORY_NOT_EMPTY));
        assertEquals(4, files.delete("/del", true));
        assertEquals(0, files.delete("/del", true));
        assertThrows(NotFoundException.class, () -> files.stat("/del"));
    }

    @Test
    public void test_a_chunked_upload_downloads_byte_for_byte() throws IOException {
        final var content = new byte[9 * 1024 * 1024 + 123];
        for (var i = 0; i < content.length; i++) {
            content[i] = (byte) (i ^ (i >>> 8) ^ (i >>> 16));
        }
        final var written = files.upload("/big/blob.bin", new ByteArrayInputStream(content));
        assertEquals("/big/blob.bin", written.path());
        assertEquals(content.length, written.size());
        assertEquals(content.length, files.stat("/big/blob.bin").size());
        assertEquals(List.of("/big/blob.bin"), paths(files.list("/big")));
        final var target = new ByteArrayOutputStream();
        assertEquals(content.length, files.download("/big/blob.bin", target));
        assertArrayEquals(content, target.toByteArray());
        assertArrayEquals(content, files.read("/big/blob.bin"));
        final var replaced = files.upload("/big/blob.bin", new ByteArrayInputStream(new byte[]{7}));
        assertNotEquals(written.version(), replaced.version());
        assertArrayEquals(new byte[]{7}, files.read("/big/blob.bin"));
        assertFalse(paths(files.list("/big")).stream().anyMatch(path -> path.contains(".upload-")));
    }
}
