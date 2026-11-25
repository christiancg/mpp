using System;

namespace Mpp.Client.Files;

public sealed record FileWritten(string Path, string Version, long Size);

public sealed record FileChunk(string Path, string Version, long Size, long Offset, ReadOnlyMemory<byte> Content, bool Eof);

public sealed record FileTextChunk(string Path, string Version, long Size, long Offset, string Content, bool Eof);

public sealed record FileEntry(string Path, FileKind Kind, long Size, string Version, DateTimeOffset ModifiedAt);

public sealed record FileMoved(string Path, string Version, long Moved);

public sealed record DirectoryCreated(string Path, string Version, long Created);
