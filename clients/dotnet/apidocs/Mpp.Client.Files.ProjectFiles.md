The files of one project: a tree of folders and files the server stores beside the project's collections. Obtained from `Project.Files()`.

Paths are `/`-separated and start at the project's root, `/`. A name takes printable ASCII except `/\:*?"<>|%#`, cannot start or end with a space or end with a dot, and two names of one folder cannot differ only by letter case. Every file and folder has a version, a string that changes on every write, which `FileWriteOptions.IfVersion` checks. Reads may run inside a transaction; writes may not.

## AppendAsync(string, ReadOnlyMemory<Byte>, CancellationToken)
Appends bytes at the end of a file, creating it and its missing parent folders when they are missing.

@param path the file
@param content the bytes to append
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the file's new version and size
@exception Mpp.Client.Errors.ConflictException with code `409-21` when the path or one of its parents is of the other kind
@exception Mpp.Client.Errors.MppException with code `400-51` when the path is not valid, or `413-2` or `413-3` when the file or the project's files would grow past their limit

## AppendTextAsync(string, string, CancellationToken)
Appends text, encoded as UTF-8, at the end of a file, creating it and its missing parent folders when they are missing.

@param path the file
@param text the text to append
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the file's new version and size
@exception System.ArgumentNullException when `text` is null
@exception Mpp.Client.Errors.ConflictException with code `409-21` when the path or one of its parents is of the other kind
@exception Mpp.Client.Errors.MppException with code `400-51` when the path is not valid, or `413-2` or `413-3` when the file or the project's files would grow past their limit

## DeleteAsync(string, bool, CancellationToken)
Deletes a file, or a folder with what it holds. The server answers OK when the path does not exist.

@param path the file or folder
@param recursive whether a folder that is not empty is deleted with its contents
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns how many files and folders were deleted, 0 when the path did not exist
@exception Mpp.Client.Errors.ConflictException with code `409-22` when the folder is not empty and `recursive` is false

## DownloadAsync(string, Stream, CancellationToken)
Copies a file into a stream, one `READ_FILE` window after another, each as large as the server's `filesMaxReadBytes` allows.

The windows must all come from one version of the file. When the version changes between two windows, the copy starts over from the first byte, at most three times, truncating the stream back to where it was. A stream that cannot seek is never restarted.

@param path the file
@param destination the stream to write to; it is neither flushed nor closed
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns how many bytes were copied
@exception System.ArgumentNullException when `destination` is null
@exception Mpp.Client.Errors.FileChangedException when the file kept changing through every restart, or changed once while copying into a stream that cannot seek
@exception Mpp.Client.Errors.NotFoundException with code `404-14` when the file does not exist
@exception Mpp.Client.Errors.ConflictException with code `409-21` when the path is a folder

## ListAsync(string, FileListOptions, CancellationToken)
Lists a folder in path order, requesting one `LIST_FILES` page after another as the enumeration goes. Each page resumes after the last path of the one before, so a folder changed meanwhile is neither repeated nor skipped where it was already read.

@param path the folder, `/` for the project's root
@param options whether to walk the subfolders, the page size and where to resume, or null for the direct children from the start
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the files and folders, the folder itself excluded
@exception Mpp.Client.Errors.NotFoundException with code `404-14` when the folder does not exist
@exception Mpp.Client.Errors.ConflictException with code `409-21` when the path is a file
@exception System.ArgumentOutOfRangeException when `PageSize` is not positive

## MakeDirectoryAsync(string, bool, CancellationToken)
Creates a folder.

@param path the folder
@param recursive whether the missing parent folders are created too, and an existing folder is accepted
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the folder's version and how many folders were created
@exception Mpp.Client.Errors.ConflictException with code `409-20` when the path exists and `recursive` is false, or `409-21` when it or a parent is a file
@exception Mpp.Client.Errors.NotFoundException with code `404-14` when a parent folder is missing and `recursive` is false

## MoveAsync(string, string, bool, CancellationToken)
Moves or renames a file or a folder with what it holds, creating the destination's missing parent folders. The move is atomic.

@param from the file or folder to move
@param to its new path
@param overwrite whether a file, or an empty folder, already at `to` is replaced by one of the same kind
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the destination's version and how many files and folders moved
@exception Mpp.Client.Errors.NotFoundException with code `404-14` when `from` does not exist
@exception Mpp.Client.Errors.ConflictException with code `409-20` when `to` exists and `overwrite` is false, `409-21` when it is of the other kind, or `409-22` when it is a folder that is not empty
@exception Mpp.Client.Errors.MppException with code `400-51` when a path is not valid, either is the project's root, or a folder would move into itself

## ReadAsync(string, CancellationToken)
Reads a whole file, as `DownloadAsync` does into memory.

@param path the file
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the file's bytes
@exception Mpp.Client.Errors.FileChangedException when the file kept changing through every restart
@exception Mpp.Client.Errors.NotFoundException with code `404-14` when the file does not exist
@exception Mpp.Client.Errors.ConflictException with code `409-21` when the path is a folder

## ReadRangeAsync(string, long, long?, CancellationToken)
Reads one window of a file's bytes.

@param path the file
@param offset where the window starts; past the end it reads nothing and reports `Eof`
@param length how many bytes to read at most, or null for as many as the server's `filesMaxReadBytes` allows, which also caps a larger value
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the window with the file's version and size
@exception System.ArgumentOutOfRangeException when `offset` is negative or `length` is not positive
@exception Mpp.Client.Errors.NotFoundException with code `404-14` when the file does not exist
@exception Mpp.Client.Errors.ConflictException with code `409-21` when the path is a folder

## ReadTextAsync(string, CancellationToken)
Reads a whole file and decodes it as UTF-8. The bytes are read as `ReadAsync` reads them and decoded here, so a window boundary never splits a character.

@param path the file
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the file's text
@exception System.Text.DecoderFallbackException when the file is not valid UTF-8
@exception Mpp.Client.Errors.FileChangedException when the file kept changing through every restart
@exception Mpp.Client.Errors.NotFoundException with code `404-14` when the file does not exist

## ReadTextRangeAsync(string, long, long?, CancellationToken)
Reads one window of a file, decoded as UTF-8 by the server.

@param path the file
@param offset where the window starts, in bytes; past the end it reads nothing and reports `Eof`
@param length how many bytes to read at most, or null for as many as the server's `filesMaxReadBytes` allows
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the window with the file's version and size
@exception System.ArgumentOutOfRangeException when `offset` is negative or `length` is not positive
@exception Mpp.Client.Errors.MppException with code `400-52` when the window is not valid UTF-8, which includes a window that splits a character
@exception Mpp.Client.Errors.NotFoundException with code `404-14` when the file does not exist

## StatAsync(string, CancellationToken)
Describes a file or a folder.

@param path the file or folder, `/` for the project's root
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns its kind, size, version and modification time
@exception Mpp.Client.Errors.NotFoundException with code `404-14` when the path does not exist

## UploadAsync(string, Stream, CancellationToken)
Writes a stream of any length to a file, replacing it atomically.

The stream is read in 4 MB chunks into a hidden sibling, `.{name}.upload-{8 random letters and digits}` in the same folder: the first chunk creates it and the others append to it. Once the stream ends, the sibling is moved over the file. Readers see the old file or the new one, never a partial upload. When a step fails, the sibling is deleted on a best-effort basis and the error is rethrown.

@param path the file; its missing parent folders are created
@param source the stream to read to its end; it is not closed
@param ct cancels the upload; the sibling is then deleted
@returns the file's new version and size
@exception System.ArgumentNullException when `source` is null
@exception System.ArgumentException when the path names no file, such as one ending in `/`
@exception Mpp.Client.Errors.ConflictException with code `409-21` when the path or one of its parents is of the other kind
@exception Mpp.Client.Errors.MppException with code `400-51` when the path is not valid, or `413-2` or `413-3` when the file or the project's files would grow past their limit

## WriteAsync(string, ReadOnlyMemory<Byte>, FileWriteOptions, CancellationToken)
Writes bytes to a file in one request. For content larger than a few megabytes use `UploadAsync`, since one request must fit the server's `maxRequestSize` once base64-encoded.

@param path the file
@param content the bytes to write
@param options the write mode, whether to create the missing parent folders and the expected version, or null to replace the file and create its parents
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the file's new version and size
@exception Mpp.Client.Errors.ConflictException with code `409-20` when the mode is `Create` and the path exists, `409-21` when the path or one of its parents is of the other kind, or `409-23` when `IfVersion` does not match
@exception Mpp.Client.Errors.NotFoundException with code `404-14` when a parent folder is missing and `CreateParents` is false
@exception Mpp.Client.Errors.MppException with code `400-51` when the path is not valid, or `413-2` or `413-3` when the file or the project's files would grow past their limit

## WriteTextAsync(string, string, FileWriteOptions, CancellationToken)
Writes text, encoded as UTF-8, to a file in one request.

@param path the file
@param text the text to write
@param options the write mode, whether to create the missing parent folders and the expected version, or null to replace the file and create its parents
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the file's new version and size
@exception System.ArgumentNullException when `text` is null
@exception Mpp.Client.Errors.ConflictException with code `409-20` when the mode is `Create` and the path exists, `409-21` when the path or one of its parents is of the other kind, or `409-23` when `IfVersion` does not match
@exception Mpp.Client.Errors.NotFoundException with code `404-14` when a parent folder is missing and `CreateParents` is false
@exception Mpp.Client.Errors.MppException with code `400-51` when the path is not valid, or `413-2` or `413-3` when the file or the project's files would grow past their limit
