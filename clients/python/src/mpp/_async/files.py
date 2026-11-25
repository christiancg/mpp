from __future__ import annotations

import contextlib
from collections.abc import AsyncIterator, Iterable
from typing import BinaryIO

from mpp._internal import file_requests as wire
from mpp._internal.client_context import ClientContext
from mpp._internal.file_transfer import (
    CHUNK_BYTES,
    BufferSink,
    Sink,
    StreamSink,
    chunks,
    read_whole,
    staging_path,
)
from mpp.files_types import DirectoryCreated, FileEncoding, FileInfo, FileMoved, FileRange, FileWriteMode, FileWritten


class AsyncProjectFiles:
    def __init__(self, context: ClientContext, project: str) -> None:
        self._context = context
        self._project = project

    async def write(
        self,
        path: str,
        data: bytes | str,
        *,
        mode: FileWriteMode = FileWriteMode.REPLACE,
        create_parents: bool = True,
        if_version: str | None = None,
    ) -> FileWritten:
        request = wire.write_file(self._project, path, data, mode, create_parents, if_version)
        return wire.written(await self._context.channel.send("WRITE_FILE", request))

    async def append(self, path: str, data: bytes | str, *, create_parents: bool = True) -> FileWritten:
        return await self.write(path, data, mode=FileWriteMode.APPEND, create_parents=create_parents)

    async def upload(self, path: str, source: BinaryIO | Iterable[bytes]) -> FileWritten:
        staging = staging_path(path)
        size = 0
        try:
            mode = FileWriteMode.CREATE
            for chunk in chunks(source, CHUNK_BYTES):
                await self.write(staging, chunk, mode=mode)
                mode = FileWriteMode.APPEND
                size += len(chunk)
            if mode is FileWriteMode.CREATE:
                await self.write(staging, b"", mode=mode)
            result = await self.move(staging, path, overwrite=True)
        except BaseException:
            with contextlib.suppress(Exception):
                await self.delete(staging)
            raise
        return FileWritten(result.path, result.version, size)

    async def read_range(
        self, path: str, offset: int = 0, length: int | None = None, *, encoding: FileEncoding = FileEncoding.BASE64
    ) -> FileRange:
        request = wire.read_file(self._project, path, offset, length, encoding)
        return wire.file_range(await self._context.channel.send("READ_FILE", request))

    async def _read_into(self, path: str, sink: Sink) -> None:
        await read_whole(path, lambda offset: self.read_range(path, offset, CHUNK_BYTES), sink)

    async def read(self, path: str) -> bytes:
        sink = BufferSink()
        await self._read_into(path, sink)
        return bytes(sink.buffer)

    async def read_text(self, path: str, encoding: str = "utf-8") -> str:
        return (await self.read(path)).decode(encoding)

    async def download(self, path: str, target: BinaryIO) -> None:
        await self._read_into(path, StreamSink(target, path))

    async def stat(self, path: str) -> FileInfo:
        request = wire.stat_file(self._project, path)
        return wire.file_info(await self._context.channel.send("STAT_FILE", request))

    async def delete(self, path: str, recursive: bool = False) -> int:
        request = wire.delete_file(self._project, path, recursive)
        return wire.deleted(await self._context.channel.send("DELETE_FILE", request))

    async def move(self, from_: str, to: str, overwrite: bool = False, *, create_parents: bool = True) -> FileMoved:
        request = wire.move_file(self._project, from_, to, overwrite, create_parents)
        return wire.moved(await self._context.channel.send("MOVE_FILE", request))

    async def make_directory(self, path: str, recursive: bool = True) -> DirectoryCreated:
        request = wire.make_directory(self._project, path, recursive)
        return wire.directory_created(await self._context.channel.send("MAKE_DIRECTORY", request))

    async def _page(
        self, path: str, recursive: bool, limit: int | None, after: str | None
    ) -> tuple[tuple[FileInfo, ...], str | None]:
        request = wire.list_files(self._project, path, recursive, limit, after)
        return wire.file_page(await self._context.channel.send("LIST_FILES", request))

    async def list(
        self, path: str = "/", recursive: bool = False, *, limit: int | None = None, after: str | None = None
    ) -> AsyncIterator[FileInfo]:
        cursor = after
        while True:
            entries, cursor = await self._page(path, recursive, limit, cursor)
            for entry in entries:
                yield entry
            if cursor is None:
                return
