from __future__ import annotations

import base64
import binascii

from mpp._internal.request_encoder import request
from mpp._internal.response_decoder import text_of
from mpp._internal.values_codec import Json, JsonObject
from mpp.errors import MappingError
from mpp.files_types import (
    DirectoryCreated,
    FileEncoding,
    FileInfo,
    FileMoved,
    FileRange,
    FileWriteMode,
    FileWritten,
)


def _optional(**fields: Json) -> JsonObject:
    return {name: value for name, value in fields.items() if value is not None}


def write_file(
    project: str,
    path: str,
    data: bytes | str,
    mode: FileWriteMode,
    create_parents: bool,
    if_version: str | None,
) -> JsonObject:
    if isinstance(data, str):
        content, encoding = data, FileEncoding.UTF8
    else:
        content, encoding = base64.b64encode(data).decode("ascii"), FileEncoding.BASE64
    return {
        **request("WRITE_FILE", project),
        "path": path,
        "content": content,
        "encoding": str(encoding),
        "mode": str(mode),
        "createParents": create_parents,
        **_optional(ifVersion=if_version),
    }


def read_file(project: str, path: str, offset: int, length: int | None, encoding: FileEncoding) -> JsonObject:
    return {
        **request("READ_FILE", project),
        "path": path,
        "offset": offset,
        **_optional(length=length),
        "encoding": str(encoding),
    }


def stat_file(project: str, path: str) -> JsonObject:
    return {**request("STAT_FILE", project), "path": path}


def list_files(project: str, path: str, recursive: bool, limit: int | None, after: str | None) -> JsonObject:
    return {
        **request("LIST_FILES", project),
        "path": path,
        "recursive": recursive,
        **_optional(limit=limit, after=after),
    }


def delete_file(project: str, path: str, recursive: bool) -> JsonObject:
    return {**request("DELETE_FILE", project), "path": path, "recursive": recursive}


def move_file(project: str, source: str, target: str, overwrite: bool, create_parents: bool) -> JsonObject:
    return {
        **request("MOVE_FILE", project),
        "from": source,
        "to": target,
        "overwrite": overwrite,
        "createParents": create_parents,
    }


def make_directory(project: str, path: str, recursive: bool) -> JsonObject:
    return {**request("MAKE_DIRECTORY", project), "path": path, "recursive": recursive}


def _text(frame: JsonObject, field: str) -> str:
    return text_of(frame.get(field)) or ""


def _integer(frame: JsonObject, field: str) -> int:
    value = frame.get(field)
    if isinstance(value, bool) or not isinstance(value, int | float):
        return 0
    return int(value)


def _content(frame: JsonObject) -> bytes:
    content = _text(frame, "content")
    if text_of(frame.get("encoding")) == FileEncoding.UTF8:
        return content.encode("utf-8")
    try:
        return base64.b64decode(content, validate=True)
    except binascii.Error as error:
        raise MappingError(f"the file content is not valid base64: {error}") from error


def written(frame: JsonObject) -> FileWritten:
    return FileWritten(_text(frame, "path"), _text(frame, "version"), _integer(frame, "size"))


def file_range(frame: JsonObject) -> FileRange:
    return FileRange(
        path=_text(frame, "path"),
        version=_text(frame, "version"),
        size=_integer(frame, "size"),
        offset=_integer(frame, "offset"),
        data=_content(frame),
        eof=frame.get("eof") is True,
    )


def file_info(frame: JsonObject) -> FileInfo:
    return FileInfo(
        path=_text(frame, "path"),
        kind=_text(frame, "kind"),
        size=_integer(frame, "size"),
        version=_text(frame, "version"),
        modified_at=_integer(frame, "modifiedAt"),
    )


def file_page(frame: JsonObject) -> tuple[tuple[FileInfo, ...], str | None]:
    entries = frame.get("entries")
    rows = tuple(file_info(row) for row in entries if isinstance(row, dict)) if isinstance(entries, list) else ()
    return rows, text_of(frame.get("next"))


def moved(frame: JsonObject) -> FileMoved:
    return FileMoved(_text(frame, "path"), _text(frame, "version"), _integer(frame, "moved"))


def directory_created(frame: JsonObject) -> DirectoryCreated:
    return DirectoryCreated(_text(frame, "path"), _text(frame, "version"), _integer(frame, "created"))


def deleted(frame: JsonObject) -> int:
    return _integer(frame, "deleted")
