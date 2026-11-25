using System;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using Mpp.Client.Errors;
using Mpp.Client.Files;
using Mpp.Client.Tests.Support;
using Xunit;

namespace Mpp.Client.Tests.Unit;

public sealed partial class FileTransferTests
{
    private const int Window = 4;
    private readonly Recorder _recorder = new();

    private ProjectFiles Files => _recorder.Shop.Files();

    [Fact]
    public async Task AWholeReadPagesThroughWindowsFromTheStart()
    {
        var content = Enumerable.Range(0, 10).Select(value => (byte)value).ToArray();
        ServeWindows(content, _ => "v1");
        Assert.Equal(content, await Files.ReadAsync("/data.bin"));
        Assert.Equal([0L, 4L, 8L], Offsets());
        Assert.All(_recorder.Channel.Requests, request => Assert.Null(request["length"]));
        Assert.All(_recorder.Channel.Requests, request => Assert.Equal("base64", request["encoding"]!.GetValue<string>()));
    }

    [Fact]
    public async Task AReadThatSeesTheVersionChangeRestartsFromTheFirstByte()
    {
        var content = "añ✓cde"u8.ToArray();
        ServeWindows(content, call => call < 1 ? "v1" : "v2");
        Assert.Equal("añ✓cde", await Files.ReadTextAsync("/t.txt"));
        Assert.Equal([0L, 4L, 0L, 4L, 8L], Offsets());
    }

    [Fact]
    public async Task AFileThatKeepsChangingFailsAfterThreeRestarts()
    {
        ServeWindows(new byte[9], call => $"v{call}");
        var error = await Assert.ThrowsAsync<FileChangedException>(() => Files.ReadAsync("/hot.bin"));
        Assert.Contains("/hot.bin", error.Message, StringComparison.Ordinal);
        Assert.Equal(8, _recorder.Channel.Requests.Count);
        Assert.Equal(4, Offsets().Count(offset => offset == 0));
    }

    [Fact]
    public async Task ADownloadKeepsWhatTheStreamHeldAndTruncatesBackOnARestart()
    {
        var content = new byte[] { 10, 11, 12, 13, 14, 15 };
        ServeWindows(content, call => call < 1 ? "v1" : "v2");
        using var destination = new MemoryStream();
        destination.Write([1, 2, 3]);
        Assert.Equal(6, await Files.DownloadAsync("/d.bin", destination));
        Assert.Equal(new byte[] { 1, 2, 3, 10, 11, 12, 13, 14, 15 }, destination.ToArray());
    }

    [Fact]
    public async Task ADownloadIntoAStreamThatCannotSeekFailsOnTheFirstChange()
    {
        ServeWindows(new byte[9], call => $"v{call}");
        await using var destination = new ForwardOnlyStream();
        var error = await Assert.ThrowsAsync<FileChangedException>(() => Files.DownloadAsync("/d.bin", destination));
        Assert.Contains("cannot seek", error.Message, StringComparison.Ordinal);
        Assert.Equal(2, _recorder.Channel.Requests.Count);
        await Assert.ThrowsAsync<ArgumentNullException>(() => Files.DownloadAsync("/d.bin", null!));
    }

    [Fact]
    public async Task AnEmptyWindowWithoutEofEndsTheRead()
    {
        _recorder.Channel.Replies = request => RecordingChannel.Ok(request, new JsonObject { ["version"] = "v", ["content"] = string.Empty });
        Assert.Empty(await Files.ReadAsync("/empty"));
        Assert.Single(_recorder.Channel.Requests);
        ServeWindows([0xC3], _ => "v");
        await Assert.ThrowsAsync<DecoderFallbackException>(() => Files.ReadTextAsync("/bad.txt"));
    }

    [Fact]
    public async Task AnUploadStagesChunksInAHiddenSiblingAndMovesItOverTheFile()
    {
        var content = Enumerable.Range(0, 2 * ProjectFiles.UploadChunkBytes + 5).Select(value => (byte)(value % 251)).ToArray();
        _recorder.Channel.Replies = request => request["type"]!.GetValue<string>() == "MOVE_FILE"
            ? RecordingChannel.Ok(request, new JsonObject { ["path"] = "/dir/big.bin", ["version"] = "77", ["moved"] = 1 })
            : RecordingChannel.Ok(request);
        using var source = new MemoryStream(content);
        var written = await Files.UploadAsync("/dir/big.bin", source);
        Assert.Equal(new FileWritten("/dir/big.bin", "77", content.Length), written);
        var requests = _recorder.Channel.Requests;
        Assert.Equal(["WRITE_FILE", "WRITE_FILE", "WRITE_FILE", "MOVE_FILE"], requests.Select(request => request["type"]!.GetValue<string>()));
        var staging = requests[0]["path"]!.GetValue<string>();
        Assert.Matches(StagingName(), staging);
        Assert.StartsWith("/dir/.big.bin.upload-", staging, StringComparison.Ordinal);
        Assert.Equal(["CREATE", "APPEND", "APPEND"], requests.Take(3).Select(request => request["mode"]!.GetValue<string>()));
        Assert.All(requests.Take(3), request => Assert.Equal(staging, request["path"]!.GetValue<string>()));
        var uploaded = requests.Take(3).SelectMany(request => Convert.FromBase64String(request["content"]!.GetValue<string>())).ToArray();
        Assert.Equal(content, uploaded);
        Assert.Equal(ProjectFiles.UploadChunkBytes, Convert.FromBase64String(requests[0]["content"]!.GetValue<string>()).Length);
        var move = requests[3];
        Assert.Equal((staging, "/dir/big.bin", true), (move["from"]!.GetValue<string>(), move["to"]!.GetValue<string>(), move["overwrite"]!.GetValue<bool>()));
    }

    [Fact]
    public async Task AnUploadOfAWholeChunkOrNothingSendsOneWrite()
    {
        using (var exact = new MemoryStream(new byte[ProjectFiles.UploadChunkBytes]))
        {
            await Files.UploadAsync("top.bin", exact);
        }
        Assert.Equal(["WRITE_FILE", "MOVE_FILE"], _recorder.Channel.Requests.Select(request => request["type"]!.GetValue<string>()));
        Assert.StartsWith(".top.bin.upload-", _recorder.Channel.Requests[0]["path"]!.GetValue<string>(), StringComparison.Ordinal);
        _recorder.Channel.Requests.Clear();
        using (var empty = new MemoryStream())
        {
            Assert.Equal(0, (await Files.UploadAsync("/e.bin", empty)).Size);
        }
        Assert.Equal(("CREATE", string.Empty), (_recorder.Channel.Requests[0]["mode"]!.GetValue<string>(), _recorder.Channel.Requests[0]["content"]!.GetValue<string>()));
        Assert.Equal(2, _recorder.Channel.Requests.Count);
    }

    [Fact]
    public async Task AFailedUploadDeletesItsSiblingAndRethrows()
    {
        var writes = 0;
        _recorder.Channel.Replies = request => request["type"]!.GetValue<string>() switch
        {
            "WRITE_FILE" when ++writes == 2 => RecordingChannel.Error(request, "ERROR", "413-3"),
            _ => RecordingChannel.Ok(request),
        };
        using var source = new MemoryStream(new byte[ProjectFiles.UploadChunkBytes + 1]);
        var error = await Assert.ThrowsAsync<MppException>(() => Files.UploadAsync("/q/f.bin", source));
        Assert.Equal(ErrorCode.FileQuotaExceeded, error.Code);
        var delete = _recorder.Channel.Last;
        Assert.Equal("DELETE_FILE", delete["type"]!.GetValue<string>());
        Assert.Equal(_recorder.Channel.Requests[0]["path"]!.GetValue<string>(), delete["path"]!.GetValue<string>());
        Assert.False(delete["recursive"]!.GetValue<bool>());
    }

    [Fact]
    public async Task AFailedMoveKeepsItsErrorEvenWhenTheCleanupFails()
    {
        _recorder.Channel.Replies = request => request["type"]!.GetValue<string>() switch
        {
            "MOVE_FILE" => RecordingChannel.Error(request, "ERROR", "409-21"),
            "DELETE_FILE" => RecordingChannel.Error(request, "ERROR", "500-43"),
            _ => RecordingChannel.Ok(request),
        };
        using var source = new MemoryStream([1]);
        var error = await Assert.ThrowsAsync<ConflictException>(() => Files.UploadAsync("/f.bin", source));
        Assert.Equal(ErrorCode.FileKindMismatch, error.Code);
        Assert.Equal(["WRITE_FILE", "MOVE_FILE", "DELETE_FILE"], _recorder.Channel.Requests.Select(request => request["type"]!.GetValue<string>()));
    }

    [Fact]
    public async Task AnUploadChecksItsArgumentsBeforeSendingAnything()
    {
        using var source = new MemoryStream();
        await Assert.ThrowsAsync<ArgumentException>(() => Files.UploadAsync("/folder/", source));
        await Assert.ThrowsAsync<ArgumentException>(() => Files.UploadAsync(string.Empty, source));
        await Assert.ThrowsAsync<ArgumentNullException>(() => Files.UploadAsync("/f", null!));
        Assert.Empty(_recorder.Channel.Requests);
        Assert.NotEqual(ProjectFiles.StagingPath("/a/b"), ProjectFiles.StagingPath("/a/b"));
    }

    [GeneratedRegex(@"^/dir/\.big\.bin\.upload-[A-Za-z0-9]{8}$")]
    private static partial Regex StagingName();

    private long[] Offsets() => [.. _recorder.Channel.Requests.Select(request => request["offset"]!.GetValue<long>())];

    private void ServeWindows(byte[] content, Func<int, string> versionOfCall)
    {
        var calls = 0;
        _recorder.Channel.Replies = request =>
        {
            var offset = (int)request["offset"]!.GetValue<long>();
            var start = Math.Min(offset, content.Length);
            var window = content.AsSpan(start, Math.Min(Window, content.Length - start)).ToArray();
            return RecordingChannel.Ok(request, new JsonObject
            {
                ["path"] = request["path"]!.GetValue<string>(),
                ["version"] = versionOfCall(calls++),
                ["size"] = content.Length,
                ["offset"] = offset,
                ["content"] = Convert.ToBase64String(window),
                ["encoding"] = "base64",
                ["eof"] = start + window.Length >= content.Length,
            });
        };
    }

    private sealed class ForwardOnlyStream : Stream
    {
        public override bool CanRead => false;

        public override bool CanSeek => false;

        public override bool CanWrite => true;

        public override long Length => throw new NotSupportedException();

        public override long Position
        {
            get => throw new NotSupportedException();
            set => throw new NotSupportedException();
        }

        public override void Flush()
        {
        }

        public override int Read(byte[] buffer, int offset, int count) => throw new NotSupportedException();

        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();

        public override void SetLength(long value) => throw new NotSupportedException();

        public override void Write(byte[] buffer, int offset, int count)
        {
        }

        public override ValueTask WriteAsync(ReadOnlyMemory<byte> buffer, CancellationToken cancellationToken = default) => ValueTask.CompletedTask;
    }
}
