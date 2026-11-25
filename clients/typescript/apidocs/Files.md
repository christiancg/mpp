# Files

The files of one project: a tree of folders and binary files beside its collections, addressed by absolute paths
such as `/public/index.html`.

Obtained from `Project.files()`. Every file and folder carries a `version`, an opaque string that changes on every
write. Reads may run inside a transaction; writes may not.

## write

Writes a file. Bytes travel base64-encoded and a string as UTF-8. By default the write replaces the whole file
atomically and creates missing parent folders.

@param path the file's path
@param data the content: bytes, or text written as UTF-8
@param options the write mode (`REPLACE`, `CREATE`, which fails with `409-20` when the file exists, or `APPEND`,
which creates a missing file), whether to create missing parents, and an `ifVersion` the file must still have (`"0"`:
it must not exist), refused with `409-23` otherwise
@returns the file's path, new version and size

## append

Appends to a file, creating it and its parents when missing.

@param path the file's path
@param data the bytes, or text written as UTF-8
@returns the file's path, new version and size

## upload

Writes a file of any size from a stream. The source is cut into 4 MB chunks written to a hidden sibling,
`.{name}.upload-{8 random characters}` in the target's folder (the first chunk creates it, the rest append), which a
final move then puts in place of the target, so readers see either the old file or the whole new one. When any step
fails the sibling is deleted, on a best-effort basis, and the failure is rethrown.

@param path the file's path
@param source the content, as an async iterable or a `ReadableStream` of byte chunks of any size
@returns the file's path and new version, and the number of bytes uploaded

## read

Reads a whole file, window by window. When the file changes between two windows the read starts again from the
beginning, at most three times, and then fails with `FileChangedError`.

@param path the file's path
@returns the file's bytes

## readText

Reads a whole file, like `read`, and decodes it as UTF-8.

@param path the file's path
@returns the file's text

## readRange

Reads one window of a file. A window starting past the end is empty, with `eof` true.

@param path the file's path
@param offset the first byte to read
@param length the most bytes to read; the server caps it at its `filesMaxReadBytes`
@returns the window's bytes, with the file's version and whole size

## readTextRange

Reads one window of a file as UTF-8 text.

@param path the file's path
@param offset the first byte to read
@param length the most bytes to read; the server caps it at its `filesMaxReadBytes`
@returns the window's text, with the file's version and whole size

## stat

Describes one file or folder. A missing path is refused with `404-14`.

@param path the path
@returns its kind, size, version and modification time

## list

Lists a folder, fetching one page at a time as the iteration goes.

@param path the folder, `/` by default
@param options whether to descend into subfolders, how many entries each page fetches (1000 by default, at most
10000), and a path to start after
@returns the entries, in path order

## delete

Deletes a file or a folder. Deleting a path that does not exist succeeds. A folder that is not empty is refused with
`409-22` unless `recursive` is set.

@param path the path
@param options whether to delete a folder with everything in it
@returns how many files and folders were deleted

## move

Moves or renames a file or a folder.

@param from the current path
@param to the new path
@param options whether to replace what is at `to` (by default an existing target is refused with `409-20`), and
whether to create missing parents (the default)
@returns the new path, its version and how many files and folders moved

## makeDirectory

Creates a folder and any missing parents. Creating one that exists succeeds.

@param path the folder's path
@returns its path, version and how many folders were created
