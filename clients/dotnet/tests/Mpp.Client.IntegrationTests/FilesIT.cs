using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Threading.Tasks;
using Mpp.Client.Errors;
using Mpp.Client.Files;
using Mpp.Client.IntegrationTests.Support;
using Xunit;

namespace Mpp.Client.IntegrationTests;

public sealed class FilesIT(FilesIT.Server fixture) : IClassFixture<FilesIT.Server>
{
    private const int ReadWindowBytes = 4 * 1024 * 1024;

    private ProjectFiles Files => fixture.Client.Project("files_it").Files();

    [Fact]
    public async Task BinaryAndTextContentRoundTrips()
    {
        var bytes = Enumerable.Range(0, 256).Select(value => (byte)value).ToArray();
        var written = await Files.WriteAsync("/round/all-bytes.bin", bytes);
        Assert.Equal(("/round/all-bytes.bin", 256L), (written.Path, written.Size));
        Assert.Equal(bytes, await Files.ReadAsync("/round/all-bytes.bin"));
        var window = await Files.ReadRangeAsync("/round/all-bytes.bin", 250, 100);
        Assert.Equal(bytes[250..], window.Content.ToArray());
        Assert.Equal((250L, 256L, true, written.Version), (window.Offset, window.Size, window.Eof, window.Version));
        var past = await Files.ReadRangeAsync("/round/all-bytes.bin", 1000);
        Assert.True(past.Content.IsEmpty && past.Eof);
        const string text = "héllo, wörld ✓ 🙂\n";
        await Files.WriteTextAsync("/round/greeting.txt", text);
        Assert.Equal(text, await Files.ReadTextAsync("/round/greeting.txt"));
        Assert.Equal("héllo", (await Files.ReadTextRangeAsync("/round/greeting.txt", 0, 6)).Content);
        var created = await Files.WriteTextAsync("/round/once.txt", "1", new FileWriteOptions { Mode = FileWriteMode.Create, IfVersion = "0" });
        var exists = await Assert.ThrowsAsync<ConflictException>(() => Files.WriteTextAsync("/round/once.txt", "2", new FileWriteOptions { Mode = FileWriteMode.Create }));
        Assert.True(exists.HasCode(ErrorCode.FileExists));
        var stale = await Assert.ThrowsAsync<ConflictException>(() => Files.WriteTextAsync("/round/once.txt", "2", new FileWriteOptions { IfVersion = "1" }));
        Assert.True(stale.HasCode(ErrorCode.FileVersionConflict));
        await Files.WriteTextAsync("/round/once.txt", "3", new FileWriteOptions { IfVersion = created.Version });
        Assert.Equal("3", await Files.ReadTextAsync("/round/once.txt"));
    }

    [Fact]
    public async Task AppendsGrowTheFileAndStatDescribesIt()
    {
        await Files.AppendTextAsync("/logs/app.log", "started\n");
        var appended = await Files.AppendAsync("/logs/app.log", "stopped\n"u8.ToArray());
        Assert.Equal(16, appended.Size);
        Assert.Equal("started\nstopped\n", await Files.ReadTextAsync("/logs/app.log"));
        var file = await Files.StatAsync("/logs/app.log");
        Assert.Equal((FileKind.File, 16L, appended.Version), (file.Kind, file.Size, file.Version));
        Assert.True(file.ModifiedAt.ToUnixTimeMilliseconds() > 0);
        Assert.Equal(FileKind.Directory, (await Files.StatAsync("/logs")).Kind);
    }

    [Fact]
    public async Task ListingPagesThroughAFolder()
    {
        var made = await Files.MakeDirectoryAsync("/list/deep/er");
        Assert.Equal(3, made.Created);
        foreach (var name in new[] { "a", "b", "c", "d", "e" })
        {
            await Files.WriteTextAsync($"/list/{name}.txt", name);
        }
        var direct = await Collect(Files.ListAsync("/list", new FileListOptions { PageSize = 2 }));
        Assert.Equal(["/list/a.txt", "/list/b.txt", "/list/c.txt", "/list/d.txt", "/list/deep", "/list/e.txt"], direct.Select(entry => entry.Path).Order(StringComparer.Ordinal));
        Assert.Equal(6, direct.Select(entry => entry.Path).Distinct().Count());
        var all = await Collect(Files.ListAsync("/list", new FileListOptions { Recursive = true, PageSize = 3 }));
        Assert.Contains(all, entry => entry is { Path: "/list/deep/er", Kind: FileKind.Directory });
        Assert.Equal(7, all.Count);
        var resumed = await Collect(Files.ListAsync("/list", new FileListOptions { After = "/list/c.txt" }));
        Assert.DoesNotContain(resumed, entry => entry.Path is "/list/a.txt" or "/list/b.txt" or "/list/c.txt");
    }

    [Fact]
    public async Task MovesRenameAndRecursiveDeletesRemoveTrees()
    {
        await Files.WriteTextAsync("/mv/from/x.txt", "x");
        await Files.WriteTextAsync("/mv/from/sub/y.txt", "y");
        var moved = await Files.MoveAsync("/mv/from", "/mv/to");
        Assert.Equal(("/mv/to", 4L), (moved.Path, moved.Moved));
        Assert.Equal("y", await Files.ReadTextAsync("/mv/to/sub/y.txt"));
        var notEmpty = await Assert.ThrowsAsync<ConflictException>(() => Files.DeleteAsync("/mv"));
        Assert.True(notEmpty.HasCode(ErrorCode.DirectoryNotEmpty));
        Assert.Equal(5, await Files.DeleteAsync("/mv", true));
        Assert.Equal(0, await Files.DeleteAsync("/mv", true));
        var gone = await Assert.ThrowsAsync<NotFoundException>(() => Files.StatAsync("/mv/to/x.txt"));
        Assert.True(gone.HasCode(ErrorCode.FileNotFound));
    }

    [Fact]
    public async Task AChunkedUploadAndAWindowedDownloadKeepEveryByte()
    {
        var content = new byte[9 * 1024 * 1024 + 123];
        RandomNumberGenerator.Fill(content);
        await Files.WriteTextAsync("/big/blob.bin", "old");
        using (var source = new MemoryStream(content))
        {
            var uploaded = await Files.UploadAsync("/big/blob.bin", source);
            Assert.Equal(("/big/blob.bin", (long)content.Length), (uploaded.Path, uploaded.Size));
        }
        Assert.Equal(content.Length, (await Files.StatAsync("/big/blob.bin")).Size);
        using (var destination = new MemoryStream())
        {
            Assert.Equal(content.Length, await Files.DownloadAsync("/big/blob.bin", destination));
            Assert.True(content.AsSpan().SequenceEqual(destination.ToArray()));
        }
        var read = await Files.ReadAsync("/big/blob.bin");
        Assert.True(content.AsSpan().SequenceEqual(read));
        Assert.Equal(["/big/blob.bin"], (await Collect(Files.ListAsync("/big"))).Select(entry => entry.Path));
    }

    [Fact]
    public async Task ReadingAMissingFileIsNotFound()
    {
        var missing = await Assert.ThrowsAsync<NotFoundException>(() => Files.ReadAsync("/nowhere/none.bin"));
        Assert.True(missing.HasCode(ErrorCode.FileNotFound));
        Assert.Equal("READ_FILE", missing.Operation);
    }

    private static async Task<List<FileEntry>> Collect(IAsyncEnumerable<FileEntry> entries)
    {
        var collected = new List<FileEntry>();
        await foreach (var entry in entries)
        {
            collected.Add(entry);
        }
        return collected;
    }

    public sealed class Server : ServerFixture
    {
        protected override IReadOnlyDictionary<string, string> Config => new Dictionary<string, string>
        {
            ["filesMaxReadBytes"] = $"{ReadWindowBytes / (1024 * 1024)}Mb",
        };

        protected override Task SeedAsync() => Client.Project("files_it").CreateAsync();
    }
}
