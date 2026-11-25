# Project files

Every project has a file tree of its own: uploads, generated reports, the build of a frontend. Clients read and write it
over the wire with seven operations ([protocol.md](protocol.md#project-files)), and the [client libraries](../clients/README.md)
wrap them, chunked uploads included. A project only ever reaches its own files, with the grants it already has.

## Two folders, two volumes

The files live in `filesPath` (default `files`), never inside the data folder (`filePath`). The server refuses to
start when the two overlap, or when `filesPath` overlaps `logPath`. In Docker the image declares `/app/files` as its
own volume beside `/app/db`, so uploaded files never take space or I/O from the documents, and filling the files volume
leaves document writes working.

```
{filesPath}/
  shop/                      the project's tree, exactly as clients see it
    public/index.html
    uploads/avatars/u1.png
  .mpp/
    format.json              the folder's format stamp
    shop/
      manifest.log           the project's file index and commit log
      tmp/                   content being written, moved into the tree once committed
```

The tree holds nothing but the project's files, so ordinary tools can inspect or back it up. A file or folder copied
into a project's tree while the server is stopped is picked up at the next start as a new version (see
[Recovery](#recovery)).

```bash
docker run -p 8989:8989 -p 8990:8990 -v mpp-data:/app/db -v mpp-files:/app/files -e MPP_ADMIN_PASSWORD=... mpp
```

## Paths and names

A path is `/`-separated, the leading `/` optional; `.` and `..` are resolved first and may not climb above the root.
Names are printable ASCII without `\ : * ? " < > | % #`, may not start or end with a space or end with a dot, and
can't be a Windows device name (`CON`, `PRN`, `AUX`, `NUL`, `COM1`-`COM9`, `LPT1`-`LPT9`). A name is at most 255
characters, a path at most 1024 and 32 levels deep. Two names in one folder that differ only by letter case are
refused, so a tree behaves the same on Linux, macOS and Windows. A name starting with a dot is allowed (`.well-known`).
Symbolic links are never followed.

## Permissions

| To | Needs |
|---|---|
| `READ_FILE`, `STAT_FILE`, `LIST_FILES` | `READ` on the project |
| `WRITE_FILE`, `DELETE_FILE`, `MOVE_FILE`, `MAKE_DIRECTORY` | `READ_WRITE` on the project |

Owners and admins can do everything. A `collectionPermissions` entry doesn't reach files: files aren't a collection.
The `admin` project has no files.

## Versions and consistency

Every change gets a `version`, a string from the same clock as document versions, that changes with every write of the
file. `ifVersion` on `WRITE_FILE` makes a write conditional on it, and a reader that reads a file window by window
checks it stays the same. Within one project the changes are serialized: a `REPLACE` swaps the whole file in one step,
an `APPEND` becomes visible only once it is committed, so a reader never sees half a write.

File operations aren't transactional. Inside a transaction the reads work and the writes answer `409-6`, because a file
change would not roll back with the transaction.

## Quotas

| Key | Default | Refusal |
|---|---|---|
| `filesMaxFileSize` | `64Mb` | `413-2` |
| `filesMaxBytesPerProject` | `0` (no cap) | `413-3` |
| `filesMaxEntriesPerProject` | `100000` | `413-3` |
| `filesMaxReadBytes` | `16Mb` | (the largest window one `READ_FILE` answers) |

A project's file index is loaded into memory the first time its files are used, about 200 bytes per file or folder.
`GET_PROJECT_STATS` shows each project's `files.entries` and `files.bytes` to the callers who can read its files.

## Durability

`manifest.log` is the project's commit log. Every change is one record, forced to disk before the answer with
`syncWrites=true`:

- A `REPLACE` or `CREATE` writes the content to `tmp/` and forces it, then logs the change, then moves the file into
  the tree.
- An `APPEND` writes and forces the new bytes, then logs the file's new size.
- A delete or a move logs every path it changes in one record, then changes the tree.

A failure before the record is logged leaves nothing behind. A failure after it answers `500-42`: the change is
committed and is finished on the next access to the project's files, or at the next start.

## Recovery

The first time a project's files are used after a start, the server compares the manifest with the tree:

- a committed `REPLACE` still in `tmp/` is moved into place;
- a file longer than its committed size (an `APPEND` that never committed) is cut back;
- a deleted or moved path that is still on disk is deleted or moved;
- a logged folder that is missing is created again;
- a file or folder on disk that the manifest doesn't know is adopted as a new version, and one the manifest knows but
  that is gone is forgotten;
- `tmp/` is emptied and the manifest rewritten compactly.

A manifest damaged before its last record (not just torn at the end by a crash) makes the project's files answer
`500-43` instead of pretending the tree is empty; move the manifest aside and the next start rebuilds it from the tree.
See the [recovery runbook](operations.md#a-file-change-answered-500-42).

## Clustering

Every node holds every file. One node, the owner of the ring key `{project}|.files`, coordinates the changes of a
project's files: other nodes forward file requests to it, and it copies each change to every node and answers once a
majority has it. Anti-entropy repairs nodes that missed changes. See [clustering.md](clustering.md#project-files).

## Backups

A [`--dump`](operations.md#backups) doesn't carry project files yet. Back up `filesPath` beside it: stop the server and
copy the folder, or snapshot the volume.
