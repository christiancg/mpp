package io.github.christiancg.mpp.client.internal;

import io.github.christiancg.mpp.client.files.DirectoryCreated;
import io.github.christiancg.mpp.client.files.FileInfo;
import io.github.christiancg.mpp.client.files.FileKind;
import io.github.christiancg.mpp.client.files.FileMoved;
import io.github.christiancg.mpp.client.files.FileRange;
import io.github.christiancg.mpp.client.files.FileWritten;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public final class FileReplies {
    private static final String PATH = "path";
    private static final String VERSION = "version";
    private static final String SIZE = "size";

    private FileReplies() {
    }

    public static FileWritten written(ObjectNode reply, String requestedPath) {
        return new FileWritten(pathOf(reply, requestedPath), versionOf(reply), reply.path(SIZE).asLong(0));
    }

    public static FileMoved moved(ObjectNode reply, String requestedPath) {
        return new FileMoved(pathOf(reply, requestedPath), versionOf(reply), reply.path("moved").asLong(0));
    }

    public static DirectoryCreated directory(ObjectNode reply, String requestedPath) {
        return new DirectoryCreated(pathOf(reply, requestedPath), versionOf(reply), reply.path("created").asLong(0));
    }

    public static FileRange range(ObjectNode reply, String requestedPath) {
        return new FileRange(pathOf(reply, requestedPath), versionOf(reply), reply.path(SIZE).asLong(0),
                reply.path("offset").asLong(0), FileRequestEncoder.contentOf(reply), reply.path("eof").asBoolean(true));
    }

    public static FileInfo info(JsonNode entry, String requestedPath) {
        final var kind = FileKind.DIRECTORY.name().equals(entry.path("kind").asString(""))
                ? FileKind.DIRECTORY
                : FileKind.FILE;
        return new FileInfo(entry.path(PATH).asString(requestedPath), kind, entry.path(SIZE).asLong(0),
                versionOf(entry), Instant.ofEpochMilli(entry.path("modifiedAt").asLong(0)));
    }

    public static List<FileInfo> entries(ObjectNode reply) {
        final var entries = new ArrayList<FileInfo>();
        for (final var entry : reply.path("entries").values()) {
            if (entry.isObject()) {
                entries.add(info(entry, ""));
            }
        }
        return entries;
    }

    public static Optional<String> next(ObjectNode reply) {
        return Optional.ofNullable(ResponseDecoder.textOrNull(reply.get("next")));
    }

    public static String versionOf(JsonNode reply) {
        return reply.path(VERSION).asString("");
    }

    private static String pathOf(ObjectNode reply, String requestedPath) {
        return reply.path(PATH).asString(requestedPath);
    }
}
