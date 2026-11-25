A window of a file decoded as UTF-8 by the server, as `ProjectFiles.ReadTextRangeAsync` read it.

@param Path the file's normalized path
@param Version the file's version when the window was read
@param Size the whole file's size in bytes
@param Offset where the window starts, in bytes
@param Content the window's text, empty when the offset is at or past the end
@param Eof whether the window reaches the end of the file

## FileTextChunk(string, string, long, long, string, bool)
Creates the window.

@param Path the file's normalized path
@param Version the file's version when the window was read
@param Size the whole file's size in bytes
@param Offset where the window starts, in bytes
@param Content the window's text, empty when the offset is at or past the end
@param Eof whether the window reaches the end of the file

## Content
The window's text, empty when the offset is at or past the end.

## Eof
Whether the window reaches the end of the file.

## Offset
Where the window starts, in bytes.

## Path
The file's normalized path.

## Size
The whole file's size in bytes.

## Version
The file's version when the window was read.
