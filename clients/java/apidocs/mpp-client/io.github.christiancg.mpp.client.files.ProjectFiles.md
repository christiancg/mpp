The files of one project: a tree of folders and files kept beside the project's collections, read and written over
the wire. Paths are `/`-separated, a leading `/` is optional, and the server normalises them. Obtained from
`Project.files()`.

## UPLOAD_CHUNK_BYTES

How many bytes `upload` sends per request, 4 MB.

## READ_WINDOW_BYTES

How many bytes `read` and `download` ask for per request, 4 MB.

## MAX_READ_RESTARTS

How many times `read` starts over when the file changes while it reads, 3.

## ProjectFiles(ClientContext, String)

Internal: use `Project.files()`.

@param context the client's internal context
@param project the project

## write(String, byte[])

Replaces the file's content, creating it and any missing parent folder.

@param path the file
@param content its new content; it travels base64-encoded in one request, so use `upload` for large files
@return its new version and size

## write(String, byte[], WriteOptions)

@param path the file
@param content the bytes to write
@param options the mode, whether to create parents and the version it must have
@return its new version and size

## writeText(String, String)

Replaces the file's content with UTF-8 text, creating it and any missing parent folder.

@param path the file
@param text its new content
@return its new version and size

## writeText(String, String, WriteOptions)

@param path the file
@param text the text to write, as UTF-8
@param options the mode, whether to create parents and the version it must have
@return its new version and size

## append(String, byte[])

Adds bytes at the end of the file, creating it and any missing parent folder.

@param path the file
@param content the bytes to add
@return its new version and size

## upload(String, InputStream)

Writes a stream of any size. It sends 4 MB chunks to a hidden sibling, `.{name}.upload-{8 random alphanumerics}` in
the target's folder, then moves the sibling over the target, so a reader sees the old file or the whole new one. On a
failure it deletes the sibling, best effort, and rethrows. The stream is read to its end and left open.

@param path the file
@param content the bytes to upload
@return the target's new version and the number of bytes uploaded
@throws IOException when the stream cannot be read

## read(String)

Reads the whole file window by window. When the file changes between two windows it starts over, at most
`MAX_READ_RESTARTS` times.

@param path the file
@return its content
@throws FileChangedException when the file kept changing while it was read

## readText(String)

@param path the file
@return its content decoded as UTF-8, read like `read`
@throws FileChangedException when the file kept changing while it was read

## readRange(String, long, int)

@param path the file
@param offset the first byte to read
@param length how many bytes to read at most; the server caps one read at `filesMaxReadBytes` (16 MB by default)
@return the window, empty with `eof` set when the offset is past the end
@throws IllegalArgumentException when the offset is negative or the length below 1

## readTextRange(String, long, int)

Reads a window as UTF-8 text. The offset and length count bytes, so a window can split a multi-byte character.

@param path the file
@param offset the first byte to read
@param length how many bytes to read at most
@return the window; `FileRange.text()` gives its text
@throws IllegalArgumentException when the offset is negative or the length below 1

## download(String, OutputStream)

Writes the whole file to a stream, window by window. Unlike `read` it cannot start over, since the bytes already
written can't be taken back, so a change between two windows fails it. The stream is left open.

@param path the file
@param target where the bytes go
@return the number of bytes written
@throws IOException when the stream cannot be written
@throws FileChangedException when the file changed between two windows

## stat(String)

Nothing at the path fails with a `NotFoundException` carrying `404-14` (`FILE_NOT_FOUND`).

@param path a file or folder
@return what it is, its size and version

## list(String)

@param path a folder, `/` for the root
@return its direct children in path order, fetched a page at a time as the iteration needs them

## list(String, ListOptions)

@param path a folder, `/` for the root
@param options whether to list the subtree, the page size and where to start
@return the entries in path order, fetched a page at a time as the iteration needs them

## delete(String)

Deletes a file or an empty folder. A missing path is not an error.

@param path the file or folder
@return how many files and folders were deleted, 0 when nothing was there

## delete(String, boolean)

@param path the file or folder
@param recursive whether a folder is deleted with everything in it; without it a non-empty folder fails with `409-22`
@return how many files and folders were deleted, 0 when nothing was there

## move(String, String)

Moves or renames a file or a whole folder, creating the target's missing parents. It fails with `409-20` when the
target exists.

@param from the file or folder
@param to its new path
@return its new path and version, and how many entries moved

## move(String, String, boolean)

@param from the file or folder
@param to its new path
@param overwrite whether an existing target is replaced
@return its new path and version, and how many entries moved

## makeDirectory(String)

Creates a folder and any missing parent. An existing folder is not an error.

@param path the folder
@return its path and version, and how many folders were created
