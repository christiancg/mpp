from __future__ import annotations

import base64
from collections.abc import Callable

from mpp._internal.values_codec import JsonObject
from tests.support.recording_channel import error, ok

Hook = Callable[[JsonObject], "JsonObject | None"]


class FakeFiles:
    def __init__(self) -> None:
        self.files: dict[str, bytes] = {}
        self.versions: dict[str, int] = {}
        self.clock = 0
        self.hook: Hook = lambda request: None

    def put(self, path: str, data: bytes) -> str:
        self.clock += 1
        self.files[path] = data
        self.versions[path] = self.clock
        return str(self.clock)

    def __call__(self, request: JsonObject) -> JsonObject:
        hooked = self.hook(request)
        if hooked is not None:
            return hooked
        kind = request["type"]
        if kind == "WRITE_FILE":
            return self._write(request)
        if kind == "READ_FILE":
            return self._read(request)
        if kind == "MOVE_FILE":
            return self._move(request)
        if kind == "DELETE_FILE":
            return ok(request, deleted=1 if self.files.pop(str(request["path"]), None) is not None else 0)
        return self._list(request)

    def _write(self, request: JsonObject) -> JsonObject:
        path = str(request["path"])
        content = str(request["content"])
        data = content.encode("utf-8") if request["encoding"] == "utf8" else base64.b64decode(content)
        if request["mode"] == "CREATE" and path in self.files:
            return error(request, "ERROR", "409-20")
        if request["mode"] == "APPEND":
            data = self.files.get(path, b"") + data
        version = self.put(path, data)
        return ok(request, path=path, version=version, size=len(data))

    def _read(self, request: JsonObject) -> JsonObject:
        path = str(request["path"])
        if path not in self.files:
            return error(request, "NOT_FOUND", "404-14")
        data = self.files[path]
        offset = int(str(request["offset"]))
        length = request.get("length")
        end = len(data) if length is None else offset + int(str(length))
        window = data[offset:end]
        utf8 = request["encoding"] == "utf8"
        content = window.decode("utf-8") if utf8 else base64.b64encode(window).decode("ascii")
        return ok(
            request,
            path=path,
            version=str(self.versions[path]),
            size=len(data),
            offset=offset,
            content=content,
            encoding=request["encoding"],
            eof=end >= len(data),
        )

    def _move(self, request: JsonObject) -> JsonObject:
        source, target = str(request["from"]), str(request["to"])
        if target in self.files and request["overwrite"] is not True:
            return error(request, "ERROR", "409-20")
        version = self.put(target, self.files.pop(source))
        return ok(request, path=target, version=version, moved=1)

    def _list(self, request: JsonObject) -> JsonObject:
        names = sorted(name for name in self.files if name > str(request.get("after") or ""))
        limit = int(str(request.get("limit") or 10000))
        page = names[:limit]
        entries = [
            {"path": name, "kind": "FILE", "size": len(self.files[name]), "version": "1", "modifiedAt": 7}
            for name in page
        ]
        return ok(request, entries=entries, next=page[-1] if len(names) > limit else None)
