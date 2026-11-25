from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum


class FileEncoding(StrEnum):
    BASE64 = "base64"
    UTF8 = "utf8"


class FileWriteMode(StrEnum):
    REPLACE = "REPLACE"
    CREATE = "CREATE"
    APPEND = "APPEND"


class FileKind(StrEnum):
    FILE = "FILE"
    DIRECTORY = "DIRECTORY"


@dataclass(frozen=True, slots=True)
class FileWritten:
    path: str
    version: str
    size: int


@dataclass(frozen=True, slots=True)
class FileRange:
    path: str
    version: str
    size: int
    offset: int
    data: bytes
    eof: bool


@dataclass(frozen=True, slots=True)
class FileInfo:
    path: str
    kind: str
    size: int
    version: str
    modified_at: int

    @property
    def is_directory(self) -> bool:
        return self.kind == FileKind.DIRECTORY


@dataclass(frozen=True, slots=True)
class FileMoved:
    path: str
    version: str
    moved: int


@dataclass(frozen=True, slots=True)
class DirectoryCreated:
    path: str
    version: str
    created: int
