from __future__ import annotations

import inspect
from collections.abc import AsyncIterator, Iterator

from mpp import FileEncoding, FileInfo, FileWriteMode
from tests.fixtures.recorder import FixtureCall


async def _collect(entries: AsyncIterator[FileInfo]) -> list[FileInfo]:
    return [entry async for entry in entries]


def _drain(entries: AsyncIterator[FileInfo] | Iterator[FileInfo]) -> object:
    if inspect.isasyncgen(entries):
        return _collect(entries)
    return list(entries)


def add_file_cases(cases: dict[str, FixtureCall]) -> None:
    cases["files.write_file"] = lambda r: r.shop.files().write("/public/index.html", b"<h1>hi</h1>")
    cases["files.write_file_text"] = lambda r: r.shop.files().append("/logs/app.log", "started\n")
    cases["files.write_file_conditional"] = lambda r: r.shop.files().write(
        "/config.json", b"{}", mode=FileWriteMode.CREATE, create_parents=False, if_version="0"
    )
    cases["files.read_file"] = lambda r: r.shop.files().read_range("/public/index.html", 0, 1048576)
    cases["files.read_file_text"] = lambda r: r.shop.files().read_range(
        "/logs/app.log", 8, 4096, encoding=FileEncoding.UTF8
    )
    cases["files.stat_file"] = lambda r: r.shop.files().stat("/public/index.html")
    cases["files.list_files"] = lambda r: _drain(
        r.shop.files().list("/public", recursive=True, limit=1000, after="/public/app.js")
    )
    cases["files.delete_file"] = lambda r: r.shop.files().delete("/public", recursive=True)
    cases["files.move_file"] = lambda r: r.shop.files().move("/public", "/www")
    cases["files.make_directory"] = lambda r: r.shop.files().make_directory("/assets/img")
