# AsyncProjectFiles

The files of a project: a folder tree of binary files the server stores beside the project's collections, addressed by
absolute paths such as `/public/index.html`. Every change answers the entry's new `version`, an opaque string.

The sync class `ProjectFiles` has the same members; its methods block and return the value directly, and its `list`
returns a plain iterator.

## write

Writes a whole file: `bytes` travel as base64, a `str` as UTF-8 text.

`REPLACE` (the default) swaps the file atomically, `CREATE` refuses an existing file and `APPEND` adds to the end,
creating the file when missing. Missing parent folders are created unless `create_parents` is false. With
`if_version`, the write happens only while the file is still at that version; `"0"` means it must not exist yet.

Returns:
    the file's path, new version and size in bytes

Raises:
    ConflictError: `CREATE` on an existing file (`409-20`), a folder in the way (`409-21`) or an `if_version`
    that no longer holds (`409-23`)
    MppError: an invalid path (`400-51`), a file past `filesMaxFileSize` (`413-2`) or the project's quota (`413-3`)

## append

Appends to a file, creating it (and its parent folders) when missing. Same as `write` with `FileWriteMode.APPEND`.

## upload

Uploads a large file from a binary file object or an iterable of `bytes`, in 4 MB chunks.

The chunks go to a hidden sibling, `.{name}.upload-{random}` in the target's folder, which is then moved onto the
target with `overwrite`, so readers see either the old file or the whole new one. When a chunk or the move fails the
sibling is deleted, as far as the server can still be reached, and the error is raised.

Returns:
    the target's path and new version, and the number of bytes uploaded

## read_range

Reads one window of a file: `length` bytes (the server caps a window at `filesMaxReadBytes`) from `offset`. An offset
past the end answers an empty window with `eof` set.

`encoding` only changes how the bytes travel; `FileEncoding.UTF8` is smaller on the wire but refused (`400-52`) when the
window is not valid UTF-8, including a window that splits a character.

Raises:
    NotFoundError: no such file (`404-14`)

## read

Reads a whole file, in 4 MB windows.

When the file changes between two windows the read starts over from the beginning, at most three times.

Raises:
    NotFoundError: no such file (`404-14`)
    FileChangedError: the file kept changing while it was read

## read_text

Reads a whole file and decodes it, UTF-8 by default. It reads like `read`.

## download

Reads a whole file into a binary file object, in 4 MB windows, the way `read` does.

A restart after a change seeks the target back to where it started and truncates it; a target that cannot seek
raises `FileChangedError` at the first change instead.

## stat

Describes a file or folder.

Raises:
    NotFoundError: no such file or folder (`404-14`)

## delete

Deletes a file or folder; a folder with entries needs `recursive`. Deleting a missing path is not an error.

Returns:
    the number of files and folders deleted, `0` when the path did not exist

Raises:
    ConflictError: a non-empty folder without `recursive` (`409-22`)

## move

Moves or renames a file or folder. An existing target is refused unless `overwrite`; missing parent folders of the
target are created unless `create_parents` is false.

Returns:
    the target path, its version and the number of entries moved

## make_directory

Creates a folder, and with `recursive` (the default) its missing parents; with `recursive` an existing folder is not an
error and answers `0` folders created.

Returns:
    the folder's path, its version and the number of folders created

Raises:
    ConflictError: without `recursive`, the path already exists (`409-20`)

## list

Lists the entries directly under a folder (`/`, the project's root, by default), or every entry below it with
`recursive`, in path order. The iterator asks for the next page as it runs out, up to `limit` entries (at most 10000)
per request, starting after the path `after` when given.
