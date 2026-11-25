using System;
using System.Text.Json.Nodes;
using Mpp.Client.Files;

namespace Mpp.Client.Internal;

internal static class FileReplies
{
    public static FileWritten Written(JsonObject reply) => new(Path(reply), Version(reply), Whole(reply, "size"));

    public static FileChunk Chunk(JsonObject reply) => new(
        Path(reply),
        Version(reply),
        Whole(reply, "size"),
        Whole(reply, "offset"),
        Convert.FromBase64String(ResponseDecoder.TextOf(reply["content"]) ?? string.Empty),
        ResponseDecoder.BooleanOf(reply["eof"], false));

    public static FileTextChunk TextChunk(JsonObject reply) => new(
        Path(reply),
        Version(reply),
        Whole(reply, "size"),
        Whole(reply, "offset"),
        ResponseDecoder.TextOf(reply["content"]) ?? string.Empty,
        ResponseDecoder.BooleanOf(reply["eof"], false));

    public static FileEntry Entry(JsonObject row) => new(
        Path(row),
        ResponseDecoder.TextOf(row["kind"]) == "DIRECTORY" ? FileKind.Directory : FileKind.File,
        Whole(row, "size"),
        Version(row),
        DateTimeOffset.FromUnixTimeMilliseconds(Whole(row, "modifiedAt")));

    public static FileMoved Moved(JsonObject reply) => new(Path(reply), Version(reply), Whole(reply, "moved"));

    public static DirectoryCreated Created(JsonObject reply) => new(Path(reply), Version(reply), Whole(reply, "created"));

    public static long Whole(JsonObject reply, string field) => ResponseDecoder.WholeOf(reply[field]) ?? 0;

    private static string Path(JsonObject reply) => ResponseDecoder.TextOf(reply["path"]) ?? string.Empty;

    private static string Version(JsonObject reply) => ResponseDecoder.TextOf(reply["version"]) ?? string.Empty;
}
