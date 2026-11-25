A window of a file's bytes, as `ProjectFiles.ReadRangeAsync` read it.

@param Path the file's normalized path
@param Version the file's version when the window was read
@param Size the whole file's size in bytes
@param Offset where the window starts
@param Content the window's bytes, empty when the offset is at or past the end
@param Eof whether the window reaches the end of the file

## FileChunk(string, string, long, long, ReadOnlyMemory<Byte>, bool)
Creates the window.

@param Path the file's normalized path
@param Version the file's version when the window was read
@param Size the whole file's size in bytes
@param Offset where the window starts
@param Content the window's bytes, empty when the offset is at or past the end
@param Eof whether the window reaches the end of the file

## Content
The window's bytes, empty when the offset is at or past the end.

## Eof
Whether the window reaches the end of the file.

## Offset
Where the window starts.

## Path
The file's normalized path.

## Size
The whole file's size in bytes.

## Version
The file's version when the window was read. Two windows of one file belong together only when their versions match.
