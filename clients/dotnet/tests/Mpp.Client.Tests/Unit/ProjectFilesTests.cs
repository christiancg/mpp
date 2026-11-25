using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Text.Json.Nodes;
using System.Threading.Tasks;
using Mpp.Client.Errors;
using Mpp.Client.Files;
using Mpp.Client.Protocol;
using Mpp.Client.Tests.Support;
using Xunit;

namespace Mpp.Client.Tests.Unit;

public sealed class ProjectFilesTests
{
    private readonly Recorder _recorder = new();

    private ProjectFiles Files => _recorder.Shop.Files();

    [Fact]
    public async Task BinaryContentIsSentAsBase64WithTheDefaultOptions()
    {
        var bytes = Enumerable.Range(0, 256).Select(value => (byte)value).ToArray();
        ReplyWith("ok.write_file");
        var written = await Files.WriteAsync("/bin/all.dat", bytes);
        Assert.Equal(new FileWritten("/public/index.html", "117673399272407040", 11), written);
        var request = _recorder.Channel.Last;
        Assert.Equal(Operation.WriteFile, _recorder.Channel.Operations[^1]);
        Assert.Equal(("shop", "/bin/all.dat", "base64"), (Text(request, "projectName"), Text(request, "path"), Text(request, "encoding")));
        Assert.Equal(bytes, Convert.FromBase64String(Text(request, "content")));
        Assert.Equal(("REPLACE", true), (Text(request, "mode"), request["createParents"]!.GetValue<bool>()));
        Assert.Null(request["ifVersion"]);
    }

    [Fact]
    public async Task TextIsSentAsUtf8AndAppendsUseTheAppendMode()
    {
        await Files.WriteTextAsync("/notes.txt", "héllo ✓", new FileWriteOptions { IfVersion = "42", CreateParents = false });
        var request = _recorder.Channel.Last;
        Assert.Equal(("héllo ✓", "utf8", "42"), (Text(request, "content"), Text(request, "encoding"), Text(request, "ifVersion")));
        Assert.False(request["createParents"]!.GetValue<bool>());
        await Files.AppendAsync("/log.bin", new byte[] { 1, 2 });
        Assert.Equal(("APPEND", "base64", "AQI="), (Text(_recorder.Channel.Last, "mode"), Text(_recorder.Channel.Last, "encoding"), Text(_recorder.Channel.Last, "content")));
        var appended = await Files.AppendTextAsync("/log.txt", "line\n");
        Assert.Equal(("APPEND", "utf8"), (Text(_recorder.Channel.Last, "mode"), Text(_recorder.Channel.Last, "encoding")));
        Assert.Equal(new FileWritten(string.Empty, string.Empty, 0), appended);
        await Files.WriteAsync("/new.bin", ReadOnlyMemory<byte>.Empty, new FileWriteOptions { Mode = FileWriteMode.Create });
        Assert.Equal(("CREATE", string.Empty), (Text(_recorder.Channel.Last, "mode"), Text(_recorder.Channel.Last, "content")));
        await Files.WriteTextAsync("/plain.txt", "p");
        Assert.Equal(("REPLACE", true), (Text(_recorder.Channel.Last, "mode"), _recorder.Channel.Last["createParents"]!.GetValue<bool>()));
        await Assert.ThrowsAsync<ArgumentNullException>(() => Files.WriteTextAsync("/x", null!));
        await Assert.ThrowsAsync<ArgumentException>(() => Files.WriteAsync(string.Empty, new byte[] { 1 }));
    }

    [Fact]
    public async Task RangesDecodeTheirWindowAndCheckTheirArguments()
    {
        ReplyWith("ok.read_file");
        var chunk = await Files.ReadRangeAsync("/public/index.html", 0, 1024);
        Assert.Equal("<h1>hi</h1>", Encoding.UTF8.GetString(chunk.Content.Span));
        Assert.Equal(("/public/index.html", "117673399272407040", 11L, 0L, true), (chunk.Path, chunk.Version, chunk.Size, chunk.Offset, chunk.Eof));
        Assert.Equal(1024, _recorder.Channel.Last["length"]!.GetValue<long>());
        _recorder.Channel.Replies = request => RecordingChannel.Ok(request, new JsonObject
        {
            ["path"] = "/a.txt",
            ["version"] = "7",
            ["size"] = 20,
            ["offset"] = 8,
            ["content"] = "ñu",
            ["encoding"] = "utf8",
        });
        var text = await Files.ReadTextRangeAsync("/a.txt", 8);
        Assert.Equal(("ñu", 8L, 20L, false), (text.Content, text.Offset, text.Size, text.Eof));
        Assert.Equal("utf8", Text(_recorder.Channel.Last, "encoding"));
        Assert.Null(_recorder.Channel.Last["length"]);
        _recorder.Channel.Replies = sent => RecordingChannel.Ok(sent);
        var empty = await Files.ReadTextRangeAsync("/a.txt", 99);
        Assert.Equal((string.Empty, string.Empty, false), (empty.Content, empty.Version, empty.Eof));
        Assert.True((await Files.ReadRangeAsync("/a.txt", 99)).Content.IsEmpty);
        var sent = _recorder.Channel.Requests.Count;
        await Assert.ThrowsAsync<ArgumentOutOfRangeException>(() => Files.ReadRangeAsync("/a", -1));
        await Assert.ThrowsAsync<ArgumentOutOfRangeException>(() => Files.ReadTextRangeAsync("/a", 0, 0));
        Assert.Equal(sent, _recorder.Channel.Requests.Count);
    }

    [Fact]
    public async Task StatDeleteMoveAndMakeDirectoryDecodeTheirReplies()
    {
        ReplyWith("ok.stat_file");
        var stat = await Files.StatAsync("/public");
        Assert.Equal(new FileEntry("/public", FileKind.Directory, 0, "117673399272407040", DateTimeOffset.FromUnixTimeMilliseconds(1795553577765)), stat);
        ReplyWith("ok.delete_file");
        Assert.Equal(2, await Files.DeleteAsync("/public"));
        Assert.False(_recorder.Channel.Last["recursive"]!.GetValue<bool>());
        ReplyWith("ok.move_file");
        Assert.Equal(new FileMoved("/www", "117673399272407040", 2), await Files.MoveAsync("/public", "/www", true));
        Assert.Equal((true, true), (_recorder.Channel.Last["overwrite"]!.GetValue<bool>(), _recorder.Channel.Last["createParents"]!.GetValue<bool>()));
        ReplyWith("ok.make_directory");
        Assert.Equal(new DirectoryCreated("/assets/img", "117673399272407040", 2), await Files.MakeDirectoryAsync("/assets/img", false));
        Assert.False(_recorder.Channel.Last["recursive"]!.GetValue<bool>());
        _recorder.Channel.Replies = request => RecordingChannel.Ok(request, new JsonObject { ["kind"] = "FILE" });
        Assert.Equal(FileKind.File, (await Files.StatAsync("/x")).Kind);
        Assert.Equal(DateTimeOffset.UnixEpoch, (await Files.StatAsync("/x")).ModifiedAt);
        await Assert.ThrowsAsync<ArgumentException>(() => Files.MoveAsync("/a", string.Empty));
        await Assert.ThrowsAsync<ArgumentNullException>(() => Files.MoveAsync(null!, "/b"));
    }

    [Fact]
    public async Task ListingWalksEveryPageThroughNext()
    {
        var pages = new Dictionary<string, JsonObject>(StringComparer.Ordinal)
        {
            [string.Empty] = Page("/docs/b", Entry("/docs/a", "FILE"), Entry("/docs/b", "DIRECTORY")),
            ["/docs/b"] = Page(null, Entry("/docs/b/c", "FILE"), JsonValue.Create(1)),
        };
        _recorder.Channel.Replies = request => RecordingChannel.Ok(request, pages[request["after"]?.GetValue<string>() ?? string.Empty]);
        var listed = new List<FileEntry>();
        await foreach (var entry in Files.ListAsync("/docs"))
        {
            listed.Add(entry);
        }
        Assert.Equal(["/docs/a", "/docs/b", "/docs/b/c"], listed.Select(entry => entry.Path));
        Assert.Equal([FileKind.File, FileKind.Directory, FileKind.File], listed.Select(entry => entry.Kind));
        Assert.Equal((5L, "9"), (listed[0].Size, listed[0].Version));
        Assert.Equal(DateTimeOffset.FromUnixTimeMilliseconds(1000), listed[0].ModifiedAt);
        Assert.Equal(2, _recorder.Channel.Requests.Count);
        Assert.All(_recorder.Channel.Requests, request => Assert.Equal(("/docs", false), (Text(request, "path"), request["recursive"]!.GetValue<bool>())));
        Assert.Null(_recorder.Channel.Requests[0]["after"]);
        Assert.Null(_recorder.Channel.Requests[0]["limit"]);
        Assert.Equal("/docs/b", Text(_recorder.Channel.Requests[1], "after"));
    }

    [Fact]
    public async Task ListingStopsOnAnEmptyPageAndChecksThePageSize()
    {
        _recorder.Channel.Replies = request => RecordingChannel.Ok(request, Page("/zzz"));
        var count = 0;
        await foreach (var _ in Files.ListAsync(options: new FileListOptions { Recursive = true, PageSize = 2, After = "/m" }))
        {
            count++;
        }
        Assert.Equal(0, count);
        var request = Assert.Single(_recorder.Channel.Requests);
        Assert.Equal(("/", "/m", 2, true), (Text(request, "path"), Text(request, "after"), request["limit"]!.GetValue<int>(), request["recursive"]!.GetValue<bool>()));
        _recorder.Channel.Replies = sent => RecordingChannel.Ok(sent);
        await foreach (var _ in Files.ListAsync())
        {
            count++;
        }
        Assert.Equal(0, count);
        await Assert.ThrowsAsync<ArgumentOutOfRangeException>(async () =>
        {
            await foreach (var _ in Files.ListAsync("/", new FileListOptions { PageSize = 0 }))
            {
                count++;
            }
        });
    }

    [Fact]
    public async Task TheListFixtureDecodesAndResumesAfterItsNext()
    {
        var frame = ProtocolFixtures.Responses().Single(response => response.Name == "ok.list_files").Body["frame"]!.AsObject();
        _recorder.Channel.Replies = request => request["after"] is null ? (JsonObject)frame.DeepClone() : RecordingChannel.Ok(request, Page(null));
        var listed = new List<FileEntry>();
        await foreach (var entry in Files.ListAsync("/", new FileListOptions { Recursive = true }))
        {
            listed.Add(entry);
        }
        Assert.Equal(
            [
                new FileEntry("/public", FileKind.Directory, 0, "117673399272407040", DateTimeOffset.FromUnixTimeMilliseconds(1795553577765)),
                new FileEntry("/public/index.html", FileKind.File, 11, "117673399272407040", DateTimeOffset.FromUnixTimeMilliseconds(1795553577765)),
            ],
            listed);
        Assert.Equal("/public/index.html", Text(_recorder.Channel.Last, "after"));
    }

    [Fact]
    public async Task FileRefusalsRaiseTheirExceptionTypes()
    {
        _recorder.Channel.Replies = request => RecordingChannel.Error(request, "NOT_FOUND", "404-14");
        var missing = await Assert.ThrowsAsync<NotFoundException>(() => Files.StatAsync("/nope"));
        Assert.True(missing.HasCode(ErrorCode.FileNotFound));
        _recorder.Channel.Replies = request => RecordingChannel.Error(request, "ERROR", "409-23");
        var stale = await Assert.ThrowsAsync<ConflictException>(() => Files.WriteTextAsync("/a", "x", new FileWriteOptions { IfVersion = "1" }));
        Assert.Equal(ErrorCode.FileVersionConflict, stale.Code);
        _recorder.Channel.Replies = request => RecordingChannel.Error(request, "ERROR", "413-3");
        var full = await Assert.ThrowsAsync<MppException>(() => Files.MakeDirectoryAsync("/a"));
        Assert.Equal(ErrorCode.FileQuotaExceeded, full.Code);
    }

    [Fact]
    public void FileReadsAreRetriedAndWritesAreNot()
    {
        Assert.True(Operation.ReadFile.IsRead);
        Assert.True(Operation.StatFile.IsRead);
        Assert.True(Operation.ListFiles.IsRead);
        Assert.False(Operation.WriteFile.IsRead);
        Assert.False(Operation.MoveFile.IsRead);
        Assert.True(Operation.ReadFile.AllowedInTransaction);
        Assert.False(Operation.DeleteFile.AllowedInTransaction);
    }

    private static string Text(JsonObject request, string field) => request[field]!.GetValue<string>();

    private static JsonObject Entry(string path, string kind) => new()
    {
        ["path"] = path,
        ["kind"] = kind,
        ["size"] = 5,
        ["version"] = "9",
        ["modifiedAt"] = 1000,
    };

    private static JsonObject Page(string? next, params JsonNode[] entries) => new()
    {
        ["entries"] = new JsonArray([.. entries]),
        ["next"] = next,
    };

    private void ReplyWith(string fixture)
    {
        var frame = ProtocolFixtures.Responses().Single(response => response.Name == fixture).Body["frame"]!.AsObject();
        _recorder.Channel.Replies = _ => (JsonObject)frame.DeepClone();
    }
}
