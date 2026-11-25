from __future__ import annotations

import io
import os
from collections.abc import AsyncIterator

import pytest

from mpp import (
    AsyncMppClient,
    AsyncProject,
    ConflictError,
    FileKind,
    FileWriteMode,
    MppClient,
    NotFoundError,
)

pytestmark = pytest.mark.it

SERVER_CONFIG = {"filesPath": "files", "filesMaxReadBytes": "1Mb"}

ALL_BYTES = bytes(range(256))


@pytest.fixture
async def shop(client: AsyncMppClient) -> AsyncIterator[AsyncProject]:
    project = client.project("files_it")
    await project.create()
    yield project
    await project.drop()


async def test_binary_and_text_round_trip(shop: AsyncProject) -> None:
    files = shop.files()
    written = await files.write("/bin/all.dat", ALL_BYTES)
    assert (written.path, written.size) == ("/bin/all.dat", 256)
    assert await files.read("/bin/all.dat") == ALL_BYTES
    await files.write("/notes/hello.txt", "héllo wörld\n")
    assert await files.read_text("/notes/hello.txt") == "héllo wörld\n"
    window = await files.read_range("/bin/all.dat", 250, 100)
    assert (window.data, window.offset, window.size, window.eof) == (ALL_BYTES[250:], 250, 256, True)
    past = await files.read_range("/bin/all.dat", 1000)
    assert (past.data, past.eof) == (b"", True)


async def test_append_stat_and_conditional_writes(shop: AsyncProject) -> None:
    files = shop.files()
    first = await files.append("/logs/app.log", "started\n")
    second = await files.append("/logs/app.log", b"running\n")
    assert second.size == 16
    assert second.version != first.version
    assert await files.read("/logs/app.log") == b"started\nrunning\n"
    info = await files.stat("/logs/app.log")
    assert (info.kind, info.size, info.version, info.is_directory) == (FileKind.FILE, 16, second.version, False)
    assert info.modified_at > 0
    assert (await files.stat("/logs")).is_directory
    with pytest.raises(ConflictError) as exists:
        await files.write("/logs/app.log", b"x", mode=FileWriteMode.CREATE)
    assert exists.value.raw_code == "409-20"
    with pytest.raises(ConflictError) as stale:
        await files.write("/logs/app.log", b"x", if_version=first.version)
    assert stale.value.raw_code == "409-23"
    replaced = await files.write("/logs/app.log", b"x", if_version=second.version)
    assert replaced.size == 1


async def test_list_pages_move_and_recursive_delete(shop: AsyncProject) -> None:
    files = shop.files()
    for index in range(5):
        await files.write(f"/site/page{index}.html", f"<p>{index}</p>")
    made = await files.make_directory("/site/assets/img")
    assert made.created == 2
    top = [entry.path async for entry in files.list("/site", limit=2)]
    assert top == ["/site/assets", *(f"/site/page{index}.html" for index in range(5))]
    everything = [entry.path async for entry in files.list("/site", recursive=True, limit=3)]
    assert len(everything) == 7
    assert everything == sorted(everything)
    moved = await files.move("/site", "/www")
    assert (moved.path, moved.moved) == ("/www", 8)
    assert await files.read_text("/www/page3.html") == "<p>3</p>"
    with pytest.raises(ConflictError) as not_empty:
        await files.delete("/www")
    assert not_empty.value.raw_code == "409-22"
    assert await files.delete("/www", recursive=True) == 8
    assert await files.delete("/www") == 0
    assert [entry async for entry in files.list()] == []


async def test_a_chunked_upload_and_download_round_trip(shop: AsyncProject) -> None:
    files = shop.files()
    payload = os.urandom(9 * 1024 * 1024 + 123)
    uploaded = await files.upload("/big/blob.bin", io.BytesIO(payload))
    assert (uploaded.path, uploaded.size) == ("/big/blob.bin", len(payload))
    assert [entry.path async for entry in files.list("/big")] == ["/big/blob.bin"]
    target = io.BytesIO()
    await files.download("/big/blob.bin", target)
    assert target.getvalue() == payload
    assert await files.read("/big/blob.bin") == payload


async def test_a_missing_file_is_not_found(shop: AsyncProject) -> None:
    with pytest.raises(NotFoundError) as missing:
        await shop.files().stat("/nothing/here")
    assert missing.value.raw_code == "404-14"


def test_the_sync_api_handles_files(sync_client: MppClient) -> None:
    project = sync_client.project("files_sync")
    project.create()
    try:
        files = project.files()
        files.write("/all.bin", ALL_BYTES)
        assert files.read("/all.bin") == ALL_BYTES
        files.append("/log.txt", "a")
        files.append("/log.txt", "b")
        assert files.read_text("/log.txt") == "ab"
        payload = os.urandom(5 * 1024 * 1024)
        files.upload("/big.bin", [payload[:1000], payload[1000:]])
        target = io.BytesIO()
        files.download("/big.bin", target)
        assert target.getvalue() == payload
        assert [entry.path for entry in files.list(limit=1)] == ["/all.bin", "/big.bin", "/log.txt"]
        assert files.move("/log.txt", "/logs/log.txt").path == "/logs/log.txt"
        assert files.stat("/logs").kind == FileKind.DIRECTORY
        assert files.delete("/logs", recursive=True) == 2
        with pytest.raises(NotFoundError):
            files.read("/logs/log.txt")
    finally:
        project.drop()
