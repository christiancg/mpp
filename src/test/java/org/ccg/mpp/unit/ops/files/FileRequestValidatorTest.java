package org.ccg.mpp.unit.ops.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.ccg.mpp.data.FileEncoding;
import org.ccg.mpp.data.FileWriteMode;
import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.files.FileContentCodec;
import org.ccg.mpp.ops.req.DeleteFileRequest;
import org.ccg.mpp.ops.req.ListFilesRequest;
import org.ccg.mpp.ops.req.MakeDirectoryRequest;
import org.ccg.mpp.ops.req.MoveFileRequest;
import org.ccg.mpp.ops.req.OperationRequest;
import org.ccg.mpp.ops.req.ReadFileRequest;
import org.ccg.mpp.ops.req.RequestParser;
import org.ccg.mpp.ops.req.StatFileRequest;
import org.ccg.mpp.ops.req.WriteFileRequest;
import org.ccg.mpp.ops.req.validations.FileRequestValidator;
import org.ccg.mpp.ops.req.validations.RequestValidator;
import org.junit.jupiter.api.Test;

public class FileRequestValidatorTest {
    private static ErrorCode codeOf(OperationRequest request) {
        final var result = RequestValidator.validate(request);
        return result.isValid() ? null : result.getErrorCode();
    }

    private static WriteFileRequest write(String path, String content) {
        final var request = new WriteFileRequest("shop");
        request.setPath(path);
        request.setContent(content);
        return request;
    }

    @Test
    public void test_project_and_paths_are_checked_first() {
        assertEquals(ErrorCode.VALIDATION_ERROR, codeOf(new StatFileRequest("admin")));
        assertEquals(ErrorCode.FILE_PATH_INVALID, codeOf(new StatFileRequest("shop")));
        assertEquals(ErrorCode.FILE_PATH_INVALID, codeOf(write("/a/../../b", "")));
        assertEquals(ErrorCode.FILE_PATH_INVALID, codeOf(new DeleteFileRequest("shop")));
        assertEquals(ErrorCode.FILE_PATH_INVALID, codeOf(new MakeDirectoryRequest("shop")));
        final var move = new MoveFileRequest("shop");
        move.setFrom("/a");
        assertEquals(ErrorCode.FILE_PATH_INVALID, codeOf(move));
        move.setTo("/b");
        assertNull(codeOf(move));
    }

    @Test
    public void test_write_content_encoding_mode_and_version() {
        assertNull(codeOf(write("/a", "")));
        assertEquals(ErrorCode.FILE_CONTENT_INVALID, codeOf(write("/a", null)));
        final var moded = write("/a", "x");
        moded.setMode(FileWriteMode.APPEND);
        moded.setEncoding(FileEncoding.UTF8);
        assertNull(codeOf(moded));
        final var versioned = write("/a", "x");
        versioned.setIfVersion("-1");
        assertEquals(ErrorCode.VALIDATION_ERROR, codeOf(versioned));
        versioned.setIfVersion("abc");
        assertEquals(ErrorCode.VALIDATION_ERROR, codeOf(versioned));
        versioned.setIfVersion("0");
        assertNull(codeOf(versioned));
        final var raw = write("/a", null);
        raw.setRawContent(new byte[0]);
        assertNull(codeOf(raw));
    }

    @Test
    public void test_read_and_list_bounds() {
        final var read = new ReadFileRequest("shop");
        read.setPath("/a");
        assertNull(codeOf(read));
        read.setOffset(-1);
        assertEquals(ErrorCode.VALIDATION_ERROR, codeOf(read));
        read.setOffset(0);
        read.setLength(0L);
        assertEquals(ErrorCode.VALIDATION_ERROR, codeOf(read));
        read.setLength(5L);
        read.setEncoding(FileEncoding.UTF8);
        assertNull(codeOf(read));
        final var list = new ListFilesRequest("shop");
        assertNull(codeOf(list));
        list.setPath("/../x");
        assertEquals(ErrorCode.FILE_PATH_INVALID, codeOf(list));
        list.setPath("/x");
        list.setLimit(0);
        assertEquals(ErrorCode.VALIDATION_ERROR, codeOf(list));
        list.setLimit(10_001);
        assertEquals(ErrorCode.VALIDATION_ERROR, codeOf(list));
        list.setLimit(10_000);
        assertNull(codeOf(list));
    }

    private static OperationRequest parsed(String options) {
        return RequestParser.parseRequest("{\"type\":\"WRITE_FILE\",\"projectName\":\"shop\",\"path\":\"/a\","
                + "\"content\":\"x\"" + options + "}");
    }

    @Test
    public void test_wire_options_bind_to_enums() {
        final var bound = assertInstanceOf(WriteFileRequest.class,
                parsed(",\"encoding\":\"UTF8\",\"mode\":\"append\""));
        assertEquals(FileEncoding.UTF8, bound.getEncoding());
        assertEquals(FileWriteMode.APPEND, bound.getMode());
        assertNull(codeOf(bound));
        final var defaults = assertInstanceOf(WriteFileRequest.class, parsed(",\"encoding\":null"));
        assertNull(defaults.getEncoding());
        assertNull(defaults.getMode());
        assertNull(codeOf(defaults));
        assertEquals(ErrorCode.FILE_CONTENT_INVALID, codeOf(parsed(",\"encoding\":\"latin1\"")));
        assertEquals(ErrorCode.FILE_CONTENT_INVALID, codeOf(parsed(",\"encoding\":7")));
        assertEquals(ErrorCode.FILE_CONTENT_INVALID, codeOf(parsed(",\"mode\":\"TRUNCATE\"")));
        assertEquals(ErrorCode.FILE_CONTENT_INVALID, codeOf(parsed(",\"mode\":[]")));
        final var read = RequestParser
                .parseRequest("{\"type\":\"READ_FILE\",\"projectName\":\"shop\",\"path\":\"/a\",\"encoding\":\"hex\"}");
        assertEquals(ErrorCode.FILE_CONTENT_INVALID, codeOf(read));
        final var utf8Read = assertInstanceOf(ReadFileRequest.class, RequestParser.parseRequest(
                "{\"type\":\"READ_FILE\",\"projectName\":\"shop\",\"path\":\"/a\",\"encoding\":\"utf8\"}"));
        assertEquals(FileEncoding.UTF8, utf8Read.getEncoding());
    }

    @Test
    public void test_enums_and_versions_parse() {
        assertEquals(FileWriteMode.CREATE, FileWriteMode.parse("create"));
        assertNull(FileWriteMode.parse("x"));
        assertEquals(FileEncoding.BASE64, FileEncoding.parse("Base64"));
        assertEquals(FileEncoding.UTF8, FileEncoding.parse("UTF8"));
        assertNull(FileEncoding.parse("utf-8"));
        assertEquals(FileEncoding.BASE64, FileEncoding.orDefault(null));
        assertEquals(12L, FileRequestValidator.versionOf("12"));
        assertNull(FileRequestValidator.versionOf("-3"));
    }

    @Test
    public void test_codec_round_trips_and_refuses_bad_input() throws Exception {
        final var bytes = "ünïcode".getBytes(StandardCharsets.UTF_8);
        assertEquals("ünïcode", FileContentCodec.encode(bytes, FileEncoding.UTF8));
        assertEquals("ünïcode",
                new String(FileContentCodec.decode("ünïcode", FileEncoding.UTF8), StandardCharsets.UTF_8));
        final var base64 = FileContentCodec.encode(bytes, FileEncoding.BASE64);
        assertTrue(new String(FileContentCodec.decode(base64, null), StandardCharsets.UTF_8).startsWith("ü"));
        assertThrows(FileContentCodec.InvalidFileContentException.class, () -> FileContentCodec.decode("@@", null));
        assertThrows(FileContentCodec.InvalidFileContentException.class,
                () -> FileContentCodec.encode(new byte[]{(byte) 0xC3}, FileEncoding.UTF8));
    }
}
