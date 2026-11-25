from __future__ import annotations

import secrets
import string
from collections.abc import Awaitable, Callable, Iterable, Iterator
from typing import BinaryIO, Protocol

from mpp.errors import FileChangedError
from mpp.files_types import FileRange

CHUNK_BYTES = 4 * 1024 * 1024
READ_RESTARTS = 3
_TOKEN_ALPHABET = string.ascii_letters + string.digits
_TOKEN_LENGTH = 8

FetchWindow = Callable[[int], Awaitable[FileRange]]


class Sink(Protocol):
    def write(self, data: bytes) -> None: ...

    def restart(self) -> None: ...


class BufferSink:
    def __init__(self) -> None:
        self.buffer = bytearray()

    def write(self, data: bytes) -> None:
        self.buffer += data

    def restart(self) -> None:
        self.buffer.clear()


class StreamSink:
    def __init__(self, target: BinaryIO, path: str) -> None:
        self._target = target
        self._path = path
        self._start = target.tell() if target.seekable() else None

    def write(self, data: bytes) -> None:
        self._target.write(data)

    def restart(self) -> None:
        if self._start is None:
            raise FileChangedError(f"{self._path} changed while it was downloaded into a stream that cannot seek")
        self._target.seek(self._start)
        self._target.truncate()


def staging_path(path: str) -> str:
    folder, separator, name = path.rpartition("/")
    token = "".join(secrets.choice(_TOKEN_ALPHABET) for _ in range(_TOKEN_LENGTH))
    return f"{folder}{separator}.{name}.upload-{token}"


def _rebuffered(parts: Iterable[bytes], size: int) -> Iterator[bytes]:
    pending = bytearray()
    for part in parts:
        pending += part
        while len(pending) >= size:
            yield bytes(pending[:size])
            del pending[:size]
    if pending:
        yield bytes(pending)


def chunks(source: BinaryIO | Iterable[bytes], size: int) -> Iterator[bytes]:
    reader = getattr(source, "read", None)
    if not callable(reader):
        yield from _rebuffered(source, size)
        return
    while chunk := reader(size):
        yield bytes(chunk)


async def _read_once(fetch: FetchWindow, sink: Sink) -> bool:
    offset = 0
    version: str | None = None
    while True:
        window = await fetch(offset)
        if version is not None and window.version != version:
            return False
        version = window.version
        sink.write(window.data)
        offset += len(window.data)
        if window.eof or not window.data:
            return True


async def read_whole(path: str, fetch: FetchWindow, sink: Sink) -> None:
    for attempt in range(READ_RESTARTS + 1):
        if attempt > 0:
            sink.restart()
        if await _read_once(fetch, sink):
            return
    raise FileChangedError(f"{path} kept changing while it was read ({READ_RESTARTS} restarts)")
