using System;
using System.Text.Json.Nodes;
using Mpp.Client.Files;
using static Mpp.Client.Internal.RequestEncoder;

namespace Mpp.Client.Internal;

internal static class FileRequestEncoder
{
    public const string Base64 = "base64";
    public const string Utf8 = "utf8";

    public static JsonObject Write(string project, string path, string content, string encoding, FileWriteOptions options)
    {
        var frame = PathRequest("WRITE_FILE", project, path);
        frame["content"] = content;
        frame["encoding"] = encoding;
        frame["mode"] = WireNames.Upper(options.Mode.ToString());
        frame["createParents"] = options.CreateParents;
        if (options.IfVersion is { } version)
        {
            frame["ifVersion"] = version;
        }
        return frame;
    }

    public static JsonObject Read(string project, string path, long offset, long? length, string encoding)
    {
        ArgumentOutOfRangeException.ThrowIfNegative(offset);
        var frame = PathRequest("READ_FILE", project, path);
        frame["offset"] = offset;
        if (length is { } window)
        {
            ArgumentOutOfRangeException.ThrowIfNegativeOrZero(window, nameof(length));
            frame["length"] = window;
        }
        frame["encoding"] = encoding;
        return frame;
    }

    public static JsonObject Stat(string project, string path) => PathRequest("STAT_FILE", project, path);

    public static JsonObject List(string project, string path, bool recursive, int? limit, string? after)
    {
        var frame = PathRequest("LIST_FILES", project, path);
        frame["recursive"] = recursive;
        if (limit is { } pageSize)
        {
            ArgumentOutOfRangeException.ThrowIfNegativeOrZero(pageSize, nameof(limit));
            frame["limit"] = pageSize;
        }
        if (after is not null)
        {
            frame["after"] = after;
        }
        return frame;
    }

    public static JsonObject Delete(string project, string path, bool recursive) =>
        With(PathRequest("DELETE_FILE", project, path), "recursive", recursive);

    public static JsonObject Move(string project, string from, string to, bool overwrite)
    {
        ArgumentException.ThrowIfNullOrEmpty(from);
        ArgumentException.ThrowIfNullOrEmpty(to);
        var frame = Request("MOVE_FILE", project);
        frame["from"] = from;
        frame["to"] = to;
        frame["overwrite"] = overwrite;
        frame["createParents"] = true;
        return frame;
    }

    public static JsonObject MakeDirectory(string project, string path, bool recursive) =>
        With(PathRequest("MAKE_DIRECTORY", project, path), "recursive", recursive);

    private static JsonObject PathRequest(string type, string project, string path)
    {
        ArgumentException.ThrowIfNullOrEmpty(path);
        return With(Request(type, project), "path", path);
    }
}
