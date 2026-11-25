package io.github.christiancg.mpp.client.files;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.christiancg.mpp.client.error.FileChangedException;
import io.github.christiancg.mpp.client.error.MppException;
import io.github.christiancg.mpp.client.testing.RecordingChannel;
import io.github.christiancg.mpp.client.testing.TestContexts;
import io.github.christiancg.mpp.testsupport.FakeServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

public class ProjectFilesTransferTest {
    private static final String STAGING = "/docs/\\.big\\.bin\\.upload-[A-Za-z0-9]{8}";
    private static final byte[] CONTENT = "abcdefgh".getBytes(StandardCharsets.UTF_8);
    private static final int WINDOW = 3;

    private final RecordingChannel channel = new RecordingChannel();
    private final ProjectFiles files = new ProjectFiles(TestContexts.recording(channel), "shop");

    private static byte[] bytes(int length) {
        final var bytes = new byte[length];
        for (var i = 0; i < length; i++) {
            bytes[i] = (byte) i;
        }
        return bytes;
    }

    private List<String> types() {
        return channel.requests().stream().map(request -> request.get("type").asString()).toList();
    }

    private void serveWindows(IntFunction<String> versionOfCall) {
        final var calls = new AtomicInteger();
        channel.replyWith(request -> {
            final var offset = (int) request.get("offset").asLong();
            final var end = Math.min(CONTENT.length, offset + WINDOW);
            final var window = Arrays.copyOfRange(CONTENT, offset, end);
            return FakeServer.ok(request).put("version", versionOfCall.apply(calls.getAndIncrement()))
                    .put("size", CONTENT.length).put("offset", offset)
                    .put("content", Base64.getEncoder().encodeToString(window)).put("encoding", "base64")
                    .put("eof", end == CONTENT.length);
        });
    }

    @Test
    public void test_an_upload_creates_a_hidden_sibling_appends_the_rest_and_moves_it_over_the_target()
            throws IOException {
        final var content = bytes(ProjectFiles.UPLOAD_CHUNK_BYTES * 2 + 5);
        channel.replyWith(request -> "MOVE_FILE".equals(request.get("type").asString())
                ? FakeServer.ok(request).put("path", "/docs/big.bin").put("version", "9").put("moved", 1)
                : FakeServer.ok(request));
        final var written = files.upload("/docs/big.bin", new ByteArrayInputStream(content));
        assertEquals(new FileWritten("/docs/big.bin", "9", content.length), written);
        assertEquals(List.of("WRITE_FILE", "WRITE_FILE", "WRITE_FILE", "MOVE_FILE"), types());
        final var requests = channel.requests();
        final var staging = requests.get(0).get("path").asString();
        assertTrue(staging.matches(STAGING), staging);
        assertEquals("CREATE", requests.get(0).get("mode").asString());
        assertEquals("APPEND", requests.get(1).get("mode").asString());
        assertEquals("APPEND", requests.get(2).get("mode").asString());
        final var uploaded = new ByteArrayOutputStream();
        for (final var request : requests.subList(0, 3)) {
            assertEquals(staging, request.get("path").asString());
            uploaded.writeBytes(Base64.getDecoder().decode(request.get("content").asString()));
        }
        assertArrayEquals(content, uploaded.toByteArray());
        final var move = requests.get(3);
        assertEquals(staging, move.get("from").asString());
        assertEquals("/docs/big.bin", move.get("to").asString());
        assertTrue(move.get("overwrite").asBoolean());
        assertTrue(move.get("createParents").asBoolean());
    }

    @Test
    public void test_an_upload_of_whole_chunks_sends_no_empty_append() throws IOException {
        files.upload("/docs/big.bin", new ByteArrayInputStream(bytes(ProjectFiles.UPLOAD_CHUNK_BYTES)));
        assertEquals(List.of("WRITE_FILE", "MOVE_FILE"), types());
    }

    @Test
    public void test_an_empty_upload_creates_an_empty_file() throws IOException {
        final var written = files.upload("big.bin", InputStream.nullInputStream());
        assertEquals(List.of("WRITE_FILE", "MOVE_FILE"), types());
        assertEquals("", channel.requests().get(0).get("content").asString());
        assertTrue(channel.requests().get(0).get("path").asString().matches("\\.big\\.bin\\.upload-[A-Za-z0-9]{8}"));
        assertEquals(new FileWritten("big.bin", "", 0), written);
    }

    @Test
    public void test_a_failed_upload_deletes_its_sibling_and_rethrows() {
        final var writes = new AtomicInteger();
        channel.replyWith(
                request -> "WRITE_FILE".equals(request.get("type").asString()) && writes.incrementAndGet() == 2
                        ? FakeServer.error(request, "ERROR", "413-3")
                        : FakeServer.ok(request));
        final var input = new ByteArrayInputStream(bytes(ProjectFiles.UPLOAD_CHUNK_BYTES + 1));
        final var failure = assertThrows(MppException.class, () -> files.upload("/docs/big.bin", input));
        assertEquals("413-3", failure.rawCode().orElseThrow());
        assertEquals(List.of("WRITE_FILE", "WRITE_FILE", "DELETE_FILE"), types());
        final var delete = channel.last();
        assertEquals(channel.requests().get(0).get("path").asString(), delete.get("path").asString());
        assertFalse(delete.get("recursive").asBoolean());
        assertEquals(0, failure.getSuppressed().length);
    }

    @Test
    public void test_a_failed_cleanup_is_suppressed_into_the_upload_failure() {
        channel.replyWith(request -> FakeServer.error(request, "ERROR", "500-43"));
        final var failure = assertThrows(MppException.class,
                () -> files.upload("/docs/big.bin", new ByteArrayInputStream(bytes(10))));
        assertEquals(List.of("WRITE_FILE", "DELETE_FILE"), types());
        assertEquals(1, failure.getSuppressed().length);
    }

    @Test
    public void test_an_unreadable_input_deletes_the_sibling_and_rethrows_the_io_failure() throws IOException {
        final var broken = new IOException("disk gone");
        try (var input = new InputStream() {
            private int reads;

            @Override
            public int read() throws IOException {
                throw broken;
            }

            @Override
            @SuppressWarnings("NullableProblems")
            public int read(byte[] buffer, int offset, int length) throws IOException {
                final var read = reads++;
                if (read == 0) {
                    return 4;
                }
                if (read == 1) {
                    return -1;
                }
                throw broken;
            }
        }) {
            final var failure = assertThrows(IOException.class, () -> files.upload("/docs/big.bin", input));
            assertSame(broken, failure);
            assertEquals(List.of("WRITE_FILE", "DELETE_FILE"), types());
        }
    }

    @Test
    public void test_an_upload_needs_a_file_name() {
        assertThrows(IllegalArgumentException.class, () -> files.upload("/", InputStream.nullInputStream()));
        assertThrows(IllegalArgumentException.class, () -> files.upload("", InputStream.nullInputStream()));
        assertTrue(channel.requests().isEmpty());
    }

    @Test
    public void test_a_staging_path_is_a_hidden_sibling_of_the_target() {
        assertTrue(ProjectFiles.stagingPathOf("/docs/big.bin").matches(STAGING));
        assertTrue(ProjectFiles.stagingPathOf("/docs/").matches("/\\.docs\\.upload-[A-Za-z0-9]{8}"));
        assertTrue(ProjectFiles.stagingPathOf("a/b").matches("a/\\.b\\.upload-[A-Za-z0-9]{8}"));
        assertNotEquals(ProjectFiles.stagingPathOf("/a"), ProjectFiles.stagingPathOf("/a"));
    }

    @Test
    public void test_a_read_pages_through_windows_until_eof() {
        serveWindows(call -> "v1");
        assertArrayEquals(CONTENT, files.read("/a"));
        final var offsets = channel.requests().stream().map(request -> request.get("offset").asLong()).toList();
        assertEquals(List.of(0L, 3L, 6L), offsets);
        assertEquals(ProjectFiles.READ_WINDOW_BYTES, channel.last().get("length").asInt());
        assertEquals("base64", channel.last().get("encoding").asString());
        assertEquals("abcdefgh", files.readText("/a"));
    }

    @Test
    public void test_a_read_restarts_from_the_start_when_the_version_changes_between_windows() {
        serveWindows(call -> call == 0 ? "v1" : "v2");
        assertArrayEquals(CONTENT, files.read("/a"));
        final var offsets = channel.requests().stream().map(request -> request.get("offset").asLong()).toList();
        assertEquals(List.of(0L, 3L, 0L, 3L, 6L), offsets);
    }

    @Test
    public void test_a_read_gives_up_after_three_restarts() {
        serveWindows(call -> "v" + call);
        final var failure = assertThrows(FileChangedException.class, () -> files.read("/a"));
        assertEquals("/a", failure.path());
        assertTrue(failure.getMessage().contains("4 time(s)"));
        assertEquals(2 * (ProjectFiles.MAX_READ_RESTARTS + 1), channel.requests().size());
    }

    @Test
    public void test_a_read_stops_on_an_empty_window_even_without_eof() {
        channel.replyWith(request -> FakeServer.ok(request).put("version", "v").put("content", "").put("eof", false));
        assertArrayEquals(new byte[0], files.read("/a"));
        assertEquals(1, channel.requests().size());
    }

    @Test
    public void test_a_download_writes_every_window_to_the_stream() throws IOException {
        serveWindows(call -> "v1");
        final var target = new ByteArrayOutputStream();
        assertEquals(CONTENT.length, files.download("/a", target));
        assertArrayEquals(CONTENT, target.toByteArray());
    }

    @Test
    public void test_a_download_fails_when_the_file_changes_after_bytes_reached_the_stream() {
        serveWindows(call -> call == 0 ? "v1" : "v2");
        final var target = new ByteArrayOutputStream();
        final var failure = assertThrows(FileChangedException.class, () -> files.download("/a", target));
        assertEquals("/a", failure.path());
        assertEquals(WINDOW, target.size());
    }

    @Test
    public void test_a_list_pages_with_next_as_after_until_next_is_null() {
        final var pages = List.of(page("/a", "/b").put("next", "/b"), page("/c", "/d").put("next", "/d"),
                page("/e").putNull("next"));
        final var calls = new AtomicInteger();
        channel.replyWith(request -> pages.get(calls.getAndIncrement()).deepCopy());
        final var paths = new ArrayList<String>();
        final var listed = files.list("/", ListOptions.builder().pageSize(2).build());
        listed.forEach(info -> paths.add(info.path()));
        assertEquals(List.of("/a", "/b", "/c", "/d", "/e"), paths);
        final var requests = channel.requests();
        assertFalse(requests.get(0).has("after"));
        assertEquals("/b", requests.get(1).get("after").asString());
        assertEquals("/d", requests.get(2).get("after").asString());
        assertEquals(2, requests.get(0).get("limit").asInt());
        assertFalse(requests.get(0).get("recursive").asBoolean());
    }

    @Test
    public void test_a_list_iterator_ends_with_no_such_element_and_skips_empty_pages() {
        final var pages = List.of(page().put("next", "/a"), page("/b"));
        final var calls = new AtomicInteger();
        channel.replyWith(request -> pages.get(calls.getAndIncrement()).deepCopy());
        final var iterator = files.list("/").iterator();
        assertEquals("/b", iterator.next().path());
        assertFalse(iterator.hasNext());
        assertThrows(NoSuchElementException.class, iterator::next);
        assertFalse(channel.requests().get(0).has("limit"));
    }

    @Test
    public void test_a_list_stops_when_next_does_not_move() {
        channel.replyWith(request -> page("/a").put("next", "/a"));
        final var paths = new ArrayList<String>();
        files.list("/", ListOptions.builder().startAfter("/a").build()).forEach(info -> paths.add(info.path()));
        assertEquals(List.of("/a"), paths);
        assertEquals(1, channel.requests().size());
    }

    private static ObjectNode page(String... paths) {
        final var page = FakeServer.ok(FakeServer.MAPPER.createObjectNode().put("type", "LIST_FILES"));
        final var entries = page.putArray("entries");
        entries.add(1);
        for (final var path : paths) {
            entries.addObject().put("path", path).put("kind", "FILE").put("size", 1).put("version", "1")
                    .put("modifiedAt", 1);
        }
        return page;
    }
}
