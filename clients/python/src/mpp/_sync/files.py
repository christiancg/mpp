from __future__ import annotations

from collections.abc import Iterable, Iterator
from typing import BinaryIO

from mpp._async.files import AsyncProjectFiles
from mpp._internal.portal import SyncRuntime
from mpp.files_types import DirectoryCreated, FileEncoding, FileInfo, FileMoved, FileRange, FileWriteMode, FileWritten


class ProjectFiles:
    def __init__(self, runtime: SyncRuntime, inner: AsyncProjectFiles) -> None:
        self._runtime = runtime
        self._inner = inner

    def write(
        self,
        path: str,
        data: bytes | str,
        *,
        mode: FileWriteMode = FileWriteMode.REPLACE,
        create_parents: bool = True,
        if_version: str | None = None,
    ) -> FileWritten:
        return self._runtime.run(
            self._inner.write(path, data, mode=mode, create_parents=create_parents, if_version=if_version)
        )

    def append(self, path: str, data: bytes | str, *, create_parents: bool = True) -> FileWritten:
        return self._runtime.run(self._inner.append(path, data, create_parents=create_parents))

    def upload(self, path: str, source: BinaryIO | Iterable[bytes]) -> FileWritten:
        return self._runtime.run(self._inner.upload(path, source))

    def read_range(
        self, path: str, offset: int = 0, length: int | None = None, *, encoding: FileEncoding = FileEncoding.BASE64
    ) -> FileRange:
        return self._runtime.run(self._inner.read_range(path, offset, length, encoding=encoding))

    def read(self, path: str) -> bytes:
        return self._runtime.run(self._inner.read(path))

    def read_text(self, path: str, encoding: str = "utf-8") -> str:
        return self._runtime.run(self._inner.read_text(path, encoding))

    def download(self, path: str, target: BinaryIO) -> None:
        self._runtime.run(self._inner.download(path, target))

    def stat(self, path: str) -> FileInfo:
        return self._runtime.run(self._inner.stat(path))

    def delete(self, path: str, recursive: bool = False) -> int:
        return self._runtime.run(self._inner.delete(path, recursive))

    def move(self, from_: str, to: str, overwrite: bool = False, *, create_parents: bool = True) -> FileMoved:
        return self._runtime.run(self._inner.move(from_, to, overwrite, create_parents=create_parents))

    def make_directory(self, path: str, recursive: bool = True) -> DirectoryCreated:
        return self._runtime.run(self._inner.make_directory(path, recursive))

    def list(
        self, path: str = "/", recursive: bool = False, *, limit: int | None = None, after: str | None = None
    ) -> Iterator[FileInfo]:
        cursor = after
        while True:
            entries, cursor = self._runtime.run(self._inner._page(path, recursive, limit, cursor))
            yield from entries
            if cursor is None:
                return
