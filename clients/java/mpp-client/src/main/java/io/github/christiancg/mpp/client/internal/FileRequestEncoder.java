package io.github.christiancg.mpp.client.internal;

import io.github.christiancg.mpp.client.files.ListOptions;
import io.github.christiancg.mpp.client.files.WriteOptions;
import io.github.christiancg.mpp.client.protocol.Operation;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import tools.jackson.databind.node.ObjectNode;

public final class FileRequestEncoder {
    public static final String BASE64 = "base64";
    public static final String UTF8 = "utf8";
    private static final String PATH = "path";
    private static final String CONTENT = "content";
    private static final String ENCODING = "encoding";
    private static final String CREATE_PARENTS = "createParents";
    private static final String RECURSIVE = "recursive";

    private FileRequestEncoder() {
    }

    public static ObjectNode write(String project, String path, byte[] content, WriteOptions options) {
        return write(project, path, Base64.getEncoder().encodeToString(content), BASE64, options);
    }

    public static ObjectNode writeText(String project, String path, String text, WriteOptions options) {
        return write(project, path, text, UTF8, options);
    }

    public static ObjectNode read(String project, String path, long offset, int length, String encoding) {
        return RequestEncoder.of(Operation.READ_FILE, project).put(PATH, path).put("offset", offset)
                .put("length", length).put(ENCODING, encoding);
    }

    public static ObjectNode stat(String project, String path) {
        return RequestEncoder.of(Operation.STAT_FILE, project).put(PATH, path);
    }

    public static ObjectNode list(String project, String path, ListOptions options, String after) {
        final var request = RequestEncoder.of(Operation.LIST_FILES, project).put(PATH, path).put(RECURSIVE,
                options.recursive());
        options.pageSize().ifPresent(limit -> request.put("limit", limit));
        if (after != null) {
            request.put("after", after);
        }
        return request;
    }

    public static ObjectNode delete(String project, String path, boolean recursive) {
        return RequestEncoder.of(Operation.DELETE_FILE, project).put(PATH, path).put(RECURSIVE, recursive);
    }

    public static ObjectNode move(String project, String from, String to, boolean overwrite) {
        return RequestEncoder.of(Operation.MOVE_FILE, project).put("from", from).put("to", to)
                .put("overwrite", overwrite).put(CREATE_PARENTS, true);
    }

    public static ObjectNode makeDirectory(String project, String path) {
        return RequestEncoder.of(Operation.MAKE_DIRECTORY, project).put(PATH, path).put(RECURSIVE, true);
    }

    public static byte[] contentOf(ObjectNode reply) {
        final var content = reply.path(CONTENT).asString("");
        if (UTF8.equals(reply.path(ENCODING).asString(BASE64))) {
            return content.getBytes(StandardCharsets.UTF_8);
        }
        return Base64.getDecoder().decode(content);
    }

    private static ObjectNode write(String project, String path, String content, String encoding,
            WriteOptions options) {
        final var request = RequestEncoder.of(Operation.WRITE_FILE, project).put(PATH, path).put(CONTENT, content)
                .put(ENCODING, encoding).put("mode", options.mode().name())
                .put(CREATE_PARENTS, options.createParents());
        options.ifVersion().ifPresent(version -> request.put("ifVersion", version));
        return request;
    }
}
