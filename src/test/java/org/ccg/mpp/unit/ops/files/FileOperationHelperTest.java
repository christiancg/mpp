package org.ccg.mpp.unit.ops.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.ccg.mpp.config.ConfigKey;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.data.FileEncoding;
import org.ccg.mpp.data.FileWriteMode;
import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.OperationType;
import org.ccg.mpp.ops.TransactionOperationHelper;
import org.ccg.mpp.ops.files.FileOperationHelper;
import org.ccg.mpp.ops.req.DeleteFileRequest;
import org.ccg.mpp.ops.req.ListFilesRequest;
import org.ccg.mpp.ops.req.MakeDirectoryRequest;
import org.ccg.mpp.ops.req.MoveFileRequest;
import org.ccg.mpp.ops.req.ReadFileRequest;
import org.ccg.mpp.ops.req.StatFileRequest;
import org.ccg.mpp.ops.req.WriteFileRequest;
import org.ccg.mpp.ops.resp.DeleteFileResponse;
import org.ccg.mpp.ops.resp.ListFilesResponse;
import org.ccg.mpp.ops.resp.MakeDirectoryResponse;
import org.ccg.mpp.ops.resp.MoveFileResponse;
import org.ccg.mpp.ops.resp.OperationResponse;
import org.ccg.mpp.ops.resp.ReadFileResponse;
import org.ccg.mpp.ops.resp.StatFileResponse;
import org.ccg.mpp.ops.resp.WriteFileResponse;
import org.ccg.mpp.test.ProjectFilesFixture;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class FileOperationHelperTest {
    private static final String PROJECT = TestGlobals.PROJECT;

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void start() throws Exception {
        ProjectFilesFixture.start();
    }

    @AfterEach
    void stop() {
        ProjectFilesFixture.stop();
    }

    private static OperationResponse write(String path, String content, String encoding, String mode) {
        final var request = new WriteFileRequest(PROJECT);
        request.setPath(path);
        request.setContent(content);
        request.setEncoding(encoding == null ? null : FileEncoding.parse(encoding));
        request.setMode(mode == null ? null : FileWriteMode.parse(mode));
        return FileOperationHelper.process(request);
    }

    private static OperationResponse writeText(String path, String text) {
        return write(path, text, "utf8", null);
    }

    private static OperationResponse read(String path, String encoding, long offset, Long length) {
        final var request = new ReadFileRequest(PROJECT);
        request.setPath(path);
        request.setEncoding(encoding == null ? null : FileEncoding.parse(encoding));
        request.setOffset(offset);
        request.setLength(length);
        return FileOperationHelper.process(request);
    }

    private static void assertCode(ErrorCode code, OperationResponse response) {
        assertEquals(code.getCode(), response.getErrorCode(), response.getMessage());
    }

    @Test
    public void test_binary_and_text_round_trip() {
        final var binary = new byte[256];
        for (var i = 0; i < binary.length; i++) {
            binary[i] = (byte) i;
        }
        final var written = assertInstanceOf(WriteFileResponse.class,
                write("/bin/all.bytes", Base64.getEncoder().encodeToString(binary), null, null));
        assertEquals(256, written.getSize());
        final var back = assertInstanceOf(ReadFileResponse.class, read("/bin/all.bytes", null, 0, null));
        assertEquals(Base64.getEncoder().encodeToString(binary), back.getContent());
        assertEquals(FileEncoding.BASE64, back.getEncoding());
        assertTrue(back.isEof());
        assertEquals(written.getVersion(), back.getVersion());
        writeText("/t.txt", "héllo ✓");
        final var text = assertInstanceOf(ReadFileResponse.class, read("t.txt", "utf8", 0, null));
        assertEquals("héllo ✓", text.getContent());
        assertCode(ErrorCode.FILE_CONTENT_INVALID, read("/bin/all.bytes", "utf8", 0, null));
    }

    @Test
    public void test_append_windows_and_the_read_cap() {
        writeText("/log", "abc");
        assertInstanceOf(WriteFileResponse.class, write("/log", "def", "utf8", "APPEND"));
        Configuration.getInstance().override(ConfigKey.FILES_MAX_READ_BYTES, "4b");
        final var first = assertInstanceOf(ReadFileResponse.class, read("/log", "utf8", 0, 100L));
        assertEquals("abcd", first.getContent());
        assertFalse(first.isEof());
        assertEquals(6, first.getSize());
        final var rest = assertInstanceOf(ReadFileResponse.class, read("/log", "utf8", 4, 2L));
        assertEquals("ef", rest.getContent());
        assertTrue(rest.isEof());
    }

    @Test
    public void test_stat_list_mkdir_move_and_delete() {
        writeText("/site/index.html", "<h1/>");
        final var stat = assertInstanceOf(StatFileResponse.class, FileOperationHelper.process(statRequest("/site")));
        assertEquals("DIRECTORY", stat.getKind());
        assertTrue(stat.estimatedJsonChars() > OperationResponse.ok(OperationType.STAT_FILE, "x").estimatedJsonChars(),
                "a refusable response sizes itself above the default estimate");
        assertTrue(stat.getModifiedAt() > 0);
        assertCode(ErrorCode.FILE_NOT_FOUND, FileOperationHelper.process(statRequest("/none")));
        final var made = new MakeDirectoryRequest(PROJECT);
        made.setPath("/site/img");
        assertEquals(1, assertInstanceOf(MakeDirectoryResponse.class, FileOperationHelper.process(made)).getCreated());
        assertEquals(0, assertInstanceOf(MakeDirectoryResponse.class, FileOperationHelper.process(made)).getCreated());
        final var list = new ListFilesRequest(PROJECT);
        list.setRecursive(true);
        list.setLimit(2);
        final var page = assertInstanceOf(ListFilesResponse.class, FileOperationHelper.process(list));
        assertEquals(2, page.getEntries().size());
        assertEquals("/site/img", page.getNext());
        final var move = new MoveFileRequest(PROJECT);
        move.setFrom("/site");
        move.setTo("/www");
        assertEquals(3, assertInstanceOf(MoveFileResponse.class, FileOperationHelper.process(move)).getMoved());
        move.setFrom("/www");
        assertEquals(0, assertInstanceOf(MoveFileResponse.class, FileOperationHelper.process(move)).getMoved());
        move.setTo("/www/inner");
        assertCode(ErrorCode.FILE_PATH_INVALID, FileOperationHelper.process(move));
        final var delete = new DeleteFileRequest(PROJECT);
        delete.setPath("/www");
        assertCode(ErrorCode.DIRECTORY_NOT_EMPTY, FileOperationHelper.process(delete));
        delete.setRecursive(true);
        assertEquals(3, assertInstanceOf(DeleteFileResponse.class, FileOperationHelper.process(delete)).getDeleted());
    }

    private static StatFileRequest statRequest(String path) {
        final var request = new StatFileRequest(PROJECT);
        request.setPath(path);
        return request;
    }

    @Test
    public void test_every_refusal_maps_to_its_error_code() {
        writeText("/f", "x");
        assertCode(ErrorCode.FILE_EXISTS, write("/f", "x", "utf8", "CREATE"));
        assertCode(ErrorCode.FILE_KIND_MISMATCH, writeText("/f/child", "x"));
        assertCode(ErrorCode.NAME_COLLIDES_ON_DISK, writeText("/F", "x"));
        assertCode(ErrorCode.FILE_CONTENT_INVALID, write("/g", "%%%", "base64", null));
        assertCode(ErrorCode.FILE_PATH_INVALID, writeText("/../x", "x"));
        final var conditional = new WriteFileRequest(PROJECT);
        conditional.setPath("/f");
        conditional.setContent("");
        conditional.setIfVersion("1");
        assertCode(ErrorCode.FILE_VERSION_CONFLICT, FileOperationHelper.process(conditional));
        Configuration.getInstance().override(ConfigKey.FILES_MAX_FILE_SIZE, "2b");
        assertCode(ErrorCode.FILE_TOO_LARGE, writeText("/big", "xyz"));
        Configuration.getInstance().override(ConfigKey.FILES_MAX_ENTRIES_PER_PROJECT, "1");
        assertCode(ErrorCode.FILE_QUOTA_EXCEEDED, writeText("/second", ""));
        final var unknown = new StatFileRequest("nosuchproject");
        unknown.setPath("/x");
        assertCode(ErrorCode.PROJECT_NOT_FOUND, FileOperationHelper.process(unknown));
    }

    @Test
    public void test_raw_content_skips_decoding() {
        final var request = new WriteFileRequest(PROJECT);
        request.setPath("/raw");
        request.setRawContent("raw bytes".getBytes(StandardCharsets.UTF_8));
        final var response = assertInstanceOf(WriteFileResponse.class, FileOperationHelper.process(request));
        assertEquals(9, response.getSize());
        assertEquals(OperationStatus.OK, response.getStatus());
        assertNull(response.getErrorCode());
    }

    @Test
    public void test_only_reads_are_allowed_inside_a_transaction() {
        assertTrue(TransactionOperationHelper.isAllowedDuringTransaction(OperationType.READ_FILE));
        assertTrue(TransactionOperationHelper.isAllowedDuringTransaction(OperationType.STAT_FILE));
        assertTrue(TransactionOperationHelper.isAllowedDuringTransaction(OperationType.LIST_FILES));
        for (final var write : new OperationType[]{OperationType.WRITE_FILE, OperationType.DELETE_FILE,
                OperationType.MOVE_FILE, OperationType.MAKE_DIRECTORY}) {
            assertFalse(TransactionOperationHelper.isAllowedDuringTransaction(write), write.name());
        }
    }
}
