using System;
using System.Collections.Generic;
using System.IO;
using System.Runtime.CompilerServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using Mpp.Client.Errors;
using Mpp.Client.Internal;
using Mpp.Client.Protocol;

namespace Mpp.Client.Files;

public sealed class ProjectFiles
{
    internal const int UploadChunkBytes = 4 * 1024 * 1024;
    internal const int MaxReadRestarts = 3;
    private const string StagingAlphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static readonly FileWriteOptions Defaults = new();
    private static readonly FileWriteOptions Appending = new() { Mode = FileWriteMode.Append };
    private static readonly UTF8Encoding StrictUtf8 = new(false, true);

    private readonly ClientContext _context;
    private readonly string _project;

    internal ProjectFiles(ClientContext context, string project)
    {
        _context = context;
        _project = project;
    }

    public async Task<FileWritten> WriteAsync(string path, ReadOnlyMemory<byte> content, FileWriteOptions? options = null, CancellationToken ct = default) =>
        FileReplies.Written(await SendAsync(
            Operation.WriteFile,
            FileRequestEncoder.Write(_project, path, Convert.ToBase64String(content.Span), FileRequestEncoder.Base64, options ?? Defaults),
            ct).ConfigureAwait(false));

    public async Task<FileWritten> WriteTextAsync(string path, string text, FileWriteOptions? options = null, CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(text);
        return FileReplies.Written(await SendAsync(
            Operation.WriteFile,
            FileRequestEncoder.Write(_project, path, text, FileRequestEncoder.Utf8, options ?? Defaults),
            ct).ConfigureAwait(false));
    }

    public Task<FileWritten> AppendAsync(string path, ReadOnlyMemory<byte> content, CancellationToken ct = default) =>
        WriteAsync(path, content, Appending, ct);

    public Task<FileWritten> AppendTextAsync(string path, string text, CancellationToken ct = default) =>
        WriteTextAsync(path, text, Appending, ct);

    public async Task<FileWritten> UploadAsync(string path, Stream source, CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(source);
        var staging = StagingPath(path);
        try
        {
            var size = await StageAsync(staging, source, ct).ConfigureAwait(false);
            var moved = await MoveAsync(staging, path, true, ct).ConfigureAwait(false);
            return new FileWritten(moved.Path, moved.Version, size);
        }
        catch
        {
            await DiscardAsync(staging).ConfigureAwait(false);
            throw;
        }
    }

    public async Task<byte[]> ReadAsync(string path, CancellationToken ct = default)
    {
        using var buffer = new MemoryStream();
        await DownloadAsync(path, buffer, ct).ConfigureAwait(false);
        return buffer.ToArray();
    }

    public async Task<string> ReadTextAsync(string path, CancellationToken ct = default) =>
        StrictUtf8.GetString(await ReadAsync(path, ct).ConfigureAwait(false));

    public async Task<FileChunk> ReadRangeAsync(string path, long offset, long? length = null, CancellationToken ct = default) =>
        FileReplies.Chunk(await SendAsync(
            Operation.ReadFile,
            FileRequestEncoder.Read(_project, path, offset, length, FileRequestEncoder.Base64),
            ct).ConfigureAwait(false));

    public async Task<FileTextChunk> ReadTextRangeAsync(string path, long offset, long? length = null, CancellationToken ct = default) =>
        FileReplies.TextChunk(await SendAsync(
            Operation.ReadFile,
            FileRequestEncoder.Read(_project, path, offset, length, FileRequestEncoder.Utf8),
            ct).ConfigureAwait(false));

    public async Task<long> DownloadAsync(string path, Stream destination, CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(destination);
        var start = destination.CanSeek ? destination.Position : -1;
        for (var restarts = 0; ; restarts++)
        {
            if (await CopyOnceAsync(path, destination, ct).ConfigureAwait(false) is { } copied)
            {
                return copied;
            }
            if (start < 0 || restarts == MaxReadRestarts)
            {
                throw new FileChangedException(start < 0
                    ? $"{path} changed while it was being read, and the destination stream cannot seek back to restart"
                    : $"{path} kept changing while it was being read, {MaxReadRestarts} restarts included");
            }
            destination.SetLength(start);
            destination.Position = start;
        }
    }

    public async Task<FileEntry> StatAsync(string path, CancellationToken ct = default) =>
        FileReplies.Entry(await SendAsync(Operation.StatFile, FileRequestEncoder.Stat(_project, path), ct).ConfigureAwait(false));

    public async IAsyncEnumerable<FileEntry> ListAsync(
        string path = "/",
        FileListOptions? options = null,
        [EnumeratorCancellation] CancellationToken ct = default)
    {
        var listing = options ?? new FileListOptions();
        var after = listing.After;
        do
        {
            var reply = await SendAsync(
                Operation.ListFiles,
                FileRequestEncoder.List(_project, path, listing.Recursive, listing.PageSize, after),
                ct).ConfigureAwait(false);
            var entries = reply["entries"] as JsonArray ?? [];
            foreach (var row in entries)
            {
                if (row is JsonObject entry)
                {
                    yield return FileReplies.Entry(entry);
                }
            }
            after = entries.Count == 0 ? null : ResponseDecoder.TextOf(reply["next"]);
        }
        while (after is not null);
    }

    public async Task<long> DeleteAsync(string path, bool recursive = false, CancellationToken ct = default) =>
        FileReplies.Whole(
            await SendAsync(Operation.DeleteFile, FileRequestEncoder.Delete(_project, path, recursive), ct).ConfigureAwait(false),
            "deleted");

    public async Task<FileMoved> MoveAsync(string from, string to, bool overwrite = false, CancellationToken ct = default) =>
        FileReplies.Moved(await SendAsync(Operation.MoveFile, FileRequestEncoder.Move(_project, from, to, overwrite), ct)
            .ConfigureAwait(false));

    public async Task<DirectoryCreated> MakeDirectoryAsync(string path, bool recursive = true, CancellationToken ct = default) =>
        FileReplies.Created(await SendAsync(Operation.MakeDirectory, FileRequestEncoder.MakeDirectory(_project, path, recursive), ct)
            .ConfigureAwait(false));

    internal static string StagingPath(string path)
    {
        ArgumentException.ThrowIfNullOrEmpty(path);
        var slash = path.LastIndexOf('/');
        var name = path[(slash + 1)..];
        return name.Length == 0
            ? throw new ArgumentException("an upload needs a file name, not a folder path", nameof(path))
            : $"{path[..(slash + 1)]}.{name}.upload-{RandomNumberGenerator.GetString(StagingAlphabet, 8)}";
    }

    private async Task<long> StageAsync(string staging, Stream source, CancellationToken ct)
    {
        var buffer = new byte[UploadChunkBytes];
        var options = new FileWriteOptions { Mode = FileWriteMode.Create };
        long size = 0;
        while (true)
        {
            var read = await source.ReadAtLeastAsync(buffer, buffer.Length, false, ct).ConfigureAwait(false);
            if (read == 0 && size > 0)
            {
                return size;
            }
            await WriteAsync(staging, buffer.AsMemory(0, read), options, ct).ConfigureAwait(false);
            size += read;
            options = Appending;
            if (read < buffer.Length)
            {
                return size;
            }
        }
    }

    private async Task<long?> CopyOnceAsync(string path, Stream destination, CancellationToken ct)
    {
        string? version = null;
        long offset = 0;
        while (true)
        {
            var chunk = await ReadRangeAsync(path, offset, null, ct).ConfigureAwait(false);
            if (version is not null && !string.Equals(version, chunk.Version, StringComparison.Ordinal))
            {
                return null;
            }
            version = chunk.Version;
            await destination.WriteAsync(chunk.Content, ct).ConfigureAwait(false);
            offset += chunk.Content.Length;
            if (chunk.Eof || chunk.Content.IsEmpty)
            {
                return offset;
            }
        }
    }

    private async Task DiscardAsync(string staging)
    {
        try
        {
            await DeleteAsync(staging, false, CancellationToken.None).ConfigureAwait(false);
        }
        catch (MppException)
        {
        }
    }

    private Task<JsonObject> SendAsync(Operation operation, JsonObject request, CancellationToken ct) =>
        _context.Channel.SendAsync(operation, request, ct);
}
