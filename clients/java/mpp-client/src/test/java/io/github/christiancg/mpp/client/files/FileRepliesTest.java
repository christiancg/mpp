package io.github.christiancg.mpp.client.files;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.christiancg.mpp.client.error.ConflictException;
import io.github.christiancg.mpp.client.error.MppException;
import io.github.christiancg.mpp.client.error.NotFoundException;
import io.github.christiancg.mpp.client.protocol.ErrorCode;
import io.github.christiancg.mpp.client.testing.RecordingChannel;
import io.github.christiancg.mpp.client.testing.TestContexts;
import io.github.christiancg.mpp.testsupport.FakeServer;
import io.github.christiancg.mpp.testsupport.ProtocolFixtures;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

public class FileRepliesTest {
    private static final String VERSION = "117673399272407040";
    private static final Instant MODIFIED = Instant.ofEpochMilli(1_795_553_577_765L);

    private final RecordingChannel channel = new RecordingChannel();
    private final ProjectFiles files = new ProjectFiles(TestContexts.recording(channel), "shop");

    private static ObjectNode fixture(String name) {
        return (ObjectNode) ProtocolFixtures.responses().stream().filter(c -> c.name().equals(name)).findFirst()
                .orElseThrow().body().get("frame");
    }

    private void replyWith(String name) {
        final var frame = fixture(name);
        channel.replyWith(request -> frame.deepCopy());
    }

    @Test
    public void test_a_write_reply_decodes() {
        replyWith("ok.write_file");
        assertEquals(new FileWritten("/public/index.html", VERSION, 11),
                files.write("/public/index.html", new byte[]{1}));
        assertEquals(new FileWritten("/public/index.html", VERSION, 11), files.writeText("/x", "text"));
        assertEquals(new FileWritten("/public/index.html", VERSION, 11), files.append("/x", new byte[0]));
        final var append = channel.last();
        assertEquals("APPEND", append.get("mode").asString());
        assertEquals("", append.get("content").asString());
        assertFalse(append.has("ifVersion"));
    }

    @Test
    public void test_a_read_range_reply_decodes_its_content() {
        replyWith("ok.read_file");
        final var range = files.readRange("/public/index.html", 0, 64);
        assertEquals("/public/index.html", range.path());
        assertEquals(VERSION, range.version());
        assertEquals(11, range.size());
        assertEquals(0, range.offset());
        assertEquals(11, range.length());
        assertEquals("<h1>hi</h1>", range.text());
        assertArrayEquals("<h1>hi</h1>".getBytes(StandardCharsets.UTF_8), range.content());
        assertNotSame(range.content(), range.content());
        assertTrue(range.eof());
    }

    @Test
    public void test_a_text_range_reply_keeps_its_utf8_content() {
        channel.replyWith(request -> FakeServer.ok(request).put("path", "/logs/app.log").put("version", "7")
                .put("size", 20).put("offset", 8).put("content", "ñandú").put("encoding", "utf8").put("eof", false));
        final var range = files.readTextRange("/logs/app.log", 8, 4096);
        assertEquals("ñandú", range.text());
        assertEquals(7, range.length());
        assertEquals(8, range.offset());
        assertFalse(range.eof());
        assertEquals("utf8", channel.last().get("encoding").asString());
    }

    @Test
    public void test_a_read_range_is_refused_before_sending_a_negative_offset_or_an_empty_length() {
        assertThrows(IllegalArgumentException.class, () -> files.readRange("/a", -1, 10));
        assertThrows(IllegalArgumentException.class, () -> files.readTextRange("/a", 0, 0));
        assertTrue(channel.requests().isEmpty());
    }

    @Test
    public void test_a_range_without_a_path_keeps_the_requested_one() {
        final var range = files.readRange("/a/b", 0, 1);
        assertEquals("/a/b", range.path());
        assertEquals("", range.version());
        assertEquals(0, range.length());
        assertTrue(range.eof());
    }

    @Test
    public void test_a_stat_reply_decodes() {
        replyWith("ok.stat_file");
        assertEquals(new FileInfo("/public", FileKind.DIRECTORY, 0, VERSION, MODIFIED), files.stat("/public"));
    }

    @Test
    public void test_a_stat_of_an_unknown_kind_reads_as_a_file() {
        channel.replyWith(request -> FakeServer.ok(request).put("kind", "SOCKET"));
        final var info = files.stat("/a");
        assertEquals(FileKind.FILE, info.kind());
        assertEquals("/a", info.path());
        assertEquals(Instant.EPOCH, info.modifiedAt());
    }

    @Test
    public void test_a_list_reply_decodes_its_entries() {
        replyWith("ok.list_files");
        final var entries = new ArrayList<FileInfo>();
        final var iterator = files.list("/public").iterator();
        entries.add(iterator.next());
        entries.add(iterator.next());
        assertEquals(List.of(new FileInfo("/public", FileKind.DIRECTORY, 0, VERSION, MODIFIED),
                new FileInfo("/public/index.html", FileKind.FILE, 11, VERSION, MODIFIED)), entries);
    }

    @Test
    public void test_delete_move_and_make_directory_replies_decode() {
        replyWith("ok.delete_file");
        assertEquals(2, files.delete("/public", true));
        assertEquals(2, files.delete("/public"));
        assertFalse(channel.last().get("recursive").asBoolean());
        replyWith("ok.move_file");
        assertEquals(new FileMoved("/www", VERSION, 2), files.move("/public", "/www", true));
        assertTrue(channel.last().get("overwrite").asBoolean());
        replyWith("ok.make_directory");
        assertEquals(new DirectoryCreated("/assets/img", VERSION, 2), files.makeDirectory("/assets/img"));
    }

    @Test
    public void test_replies_without_fields_fall_back_to_the_requested_path() {
        assertEquals(new FileWritten("/a", "", 0), files.write("/a", new byte[0]));
        assertEquals(new FileMoved("/b", "", 0), files.move("/a", "/b"));
        assertEquals(new DirectoryCreated("/c", "", 0), files.makeDirectory("/c"));
        assertEquals(0, files.delete("/c"));
    }

    @Test
    public void test_file_errors_raise_their_exception_family() {
        channel.replyWith(request -> FakeServer.error(request, "NOT_FOUND", "404-14"));
        final var missing = assertThrows(NotFoundException.class, () -> files.stat("/nope"));
        assertTrue(missing.hasCode(ErrorCode.FILE_NOT_FOUND));
        channel.replyWith(request -> FakeServer.error(request, "ERROR", "409-23"));
        final var conflict = assertThrows(ConflictException.class,
                () -> files.write("/a", new byte[0], WriteOptions.builder().ifVersion("3").build()));
        assertTrue(conflict.hasCode(ErrorCode.FILE_VERSION_CONFLICT));
        assertEquals("3", channel.last().get("ifVersion").asString());
        channel.replyWith(request -> FakeServer.error(request, "ERROR", "413-2"));
        final var tooLarge = assertThrows(MppException.class, () -> files.write("/a", new byte[0]));
        assertEquals(Optional.of(ErrorCode.FILE_TOO_LARGE), tooLarge.errorCode());
        assertInstanceOf(MppException.class, tooLarge);
    }

    @Test
    public void test_write_options_default_to_replace_with_parents_and_no_version() {
        final var defaults = WriteOptions.defaults();
        assertEquals(WriteMode.REPLACE, defaults.mode());
        assertTrue(defaults.createParents());
        assertTrue(defaults.ifVersion().isEmpty());
        assertEquals(Optional.of(WriteOptions.ABSENT), WriteOptions.builder().ifAbsent().build().ifVersion());
    }

    @Test
    public void test_list_options_default_to_one_folder_and_bound_the_page_size() {
        final var defaults = ListOptions.defaults();
        assertFalse(defaults.recursive());
        assertEquals(OptionalInt.empty(), defaults.pageSize());
        assertTrue(defaults.startAfter().isEmpty());
        assertEquals(OptionalInt.of(ListOptions.MAX_PAGE_SIZE),
                ListOptions.builder().pageSize(ListOptions.MAX_PAGE_SIZE).build().pageSize());
        assertThrows(IllegalArgumentException.class, () -> ListOptions.builder().pageSize(0).build());
        assertThrows(IllegalArgumentException.class,
                () -> ListOptions.builder().pageSize(ListOptions.MAX_PAGE_SIZE + 1).build());
    }
}
