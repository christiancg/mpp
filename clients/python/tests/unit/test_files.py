from __future__ import annotations

import io
import re

import pytest

from mpp import (
    AsyncProject,
    AsyncProjectFiles,
    ConflictError,
    FileChangedError,
    FileEncoding,
    FileKind,
    FileWriteMode,
    MappingError,
    NotFoundError,
)
from mpp._internal.file_transfer import chunks, staging_path
from mpp._internal.values_codec import JsonObject
from tests.support.fake_files import FakeFiles
from tests.support.protocol_fixtures import responses
from tests.support.recording_channel import RecordingChannel, error, ok, recording_context

FRAMES = {fixture.name: fixture.body["frame"] for fixture in responses()}
STAGING = re.compile(r"^/data/\.big\.bin\.upload-[A-Za-z0-9]{8}$")


@pytest.fixture
def small_chunks(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr("mpp._async.files.CHUNK_BYTES", 4)


def _files(channel: RecordingChannel) -> AsyncProjectFiles:
    return AsyncProject(recording_context(channel), "shop").files()


def _faked() -> tuple[RecordingChannel, FakeFiles, AsyncProjectFiles]:
    channel = RecordingChannel()
    fake = FakeFiles()
    channel.replies = fake
    return channel, fake, _files(channel)


def _replying(*frames: str) -> RecordingChannel:
    channel = RecordingChannel()
    by_type = {FRAMES[name]["type"]: FRAMES[name] for name in frames}
    channel.replies = lambda request: by_type.get(request["type"], ok(request))
    return channel


async def test_bytes_travel_as_base64_and_text_as_utf8() -> None:
    channel, fake, files = _faked()
    binary = bytes(range(256))
    written = await files.write("/bin/all.dat", binary)
    assert channel.last()["encoding"] == "base64"
    assert (written.path, written.size) == ("/bin/all.dat", 256)
    await files.write("/notes.txt", "héllo")
    assert (channel.last()["encoding"], channel.last()["content"]) == ("utf8", "héllo")
    assert fake.files["/notes.txt"] == "héllo".encode()
    assert await files.read("/bin/all.dat") == binary
    assert await files.read_text("/notes.txt") == "héllo"


async def test_append_and_conditional_writes_send_their_mode() -> None:
    channel, fake, files = _faked()
    await files.append("/log.txt", "a")
    await files.append("/log.txt", b"b", create_parents=False)
    assert (channel.last()["mode"], channel.last()["createParents"]) == ("APPEND", False)
    assert fake.files["/log.txt"] == b"ab"
    with pytest.raises(ConflictError):
        await files.write("/log.txt", b"c", mode=FileWriteMode.CREATE, if_version="0")
    assert channel.last()["ifVersion"] == "0"


async def test_read_range_decodes_both_encodings() -> None:
    channel, fake, files = _faked()
    fake.put("/a.txt", b"hello world")
    window = await files.read_range("/a.txt", 6, 3, encoding=FileEncoding.UTF8)
    assert (window.data, window.offset, window.size, window.eof) == (b"wor", 6, 11, False)
    whole = await files.read_range("/a.txt")
    assert "length" not in channel.last()
    assert (whole.data, whole.eof, whole.path, whole.version) == (b"hello world", True, "/a.txt", "1")


async def test_the_reply_fixtures_decode() -> None:
    names = ("ok.write_file", "ok.read_file", "ok.stat_file", "ok.delete_file", "ok.move_file", "ok.make_directory")
    files = _files(_replying(*names))
    written = await files.write("/public/index.html", b"<h1>hi</h1>")
    assert (written.version, written.size) == ("117673399272407040", 11)
    window = await files.read_range("/public/index.html")
    assert (window.data, window.eof) == (b"<h1>hi</h1>", True)
    info = await files.stat("/public")
    assert (info.kind, info.size, info.modified_at, info.is_directory) == (FileKind.DIRECTORY, 0, 1795553577765, True)
    assert await files.delete("/public", recursive=True) == 2
    moved = await files.move("/public", "/www")
    assert (moved.path, moved.version, moved.moved) == ("/www", "117673399272407040", 2)
    made = await files.make_directory("/assets/img")
    assert (made.path, made.created) == ("/assets/img", 2)


async def test_missing_fields_decode_to_defaults() -> None:
    files = _files(RecordingChannel())
    assert await files.delete("/gone") == 0
    info = await files.stat("/x")
    assert (info.path, info.size, info.version, info.is_directory) == ("", 0, "", False)
    assert [entry async for entry in files.list()] == []


async def test_a_reply_with_broken_base64_is_a_mapping_error() -> None:
    channel = RecordingChannel()
    channel.replies = lambda request: ok(request, content="not base64!", encoding="base64", eof=True)
    with pytest.raises(MappingError, match="not valid base64"):
        await _files(channel).read("/a")


async def test_list_pages_through_next() -> None:
    channel, fake, files = _faked()
    for name in ("a", "b", "c", "d", "e"):
        fake.put(f"/{name}", name.encode())
    listed = [entry.path async for entry in files.list("/", recursive=True, limit=2)]
    assert listed == ["/a", "/b", "/c", "/d", "/e"]
    assert [request.get("after") for request in channel.requests] == [None, "/b", "/d"]
    assert channel.last()["recursive"] is True
    later = [entry.path async for entry in files.list(after="/c")]
    assert later == ["/d", "/e"]


async def test_list_skips_rows_that_are_not_objects() -> None:
    channel = RecordingChannel()
    channel.replies = lambda request: ok(request, entries=[{"path": "/a", "kind": "FILE"}, "junk"], next=None)
    assert [entry.path async for entry in _files(channel).list()] == ["/a"]


@pytest.mark.usefixtures("small_chunks")
async def test_upload_writes_chunks_to_a_sibling_then_moves_it() -> None:
    channel, fake, files = _faked()
    fake.put("/data/big.bin", b"old")
    written = await files.upload("/data/big.bin", io.BytesIO(b"0123456789"))
    writes = [request for request in channel.requests if request["type"] == "WRITE_FILE"]
    staging = str(writes[0]["path"])
    assert STAGING.match(staging)
    assert [(request["path"], request["mode"]) for request in writes] == [
        (staging, "CREATE"),
        (staging, "APPEND"),
        (staging, "APPEND"),
    ]
    move = channel.last()
    assert (move["type"], move["from"], move["to"], move["overwrite"]) == ("MOVE_FILE", staging, "/data/big.bin", True)
    assert (written.path, written.size) == ("/data/big.bin", 10)
    assert fake.files == {"/data/big.bin": b"0123456789"}


@pytest.mark.usefixtures("small_chunks")
async def test_upload_rebuffers_an_iterable_of_bytes() -> None:
    channel, fake, files = _faked()
    await files.upload("/data/big.bin", iter([b"ab", b"cdefg", b"", b"hij"]))
    contents = [request["content"] for request in channel.requests if request["type"] == "WRITE_FILE"]
    assert len(contents) == 3
    assert fake.files["/data/big.bin"] == b"abcdefghij"


async def test_an_empty_upload_creates_an_empty_file() -> None:
    channel, fake, files = _faked()
    written = await files.upload("/empty", [])
    assert [request["type"] for request in channel.requests] == ["WRITE_FILE", "MOVE_FILE"]
    assert channel.requests[0]["content"] == ""
    assert (fake.files, written.size) == ({"/empty": b""}, 0)


@pytest.mark.usefixtures("small_chunks")
async def test_a_failed_upload_deletes_its_sibling() -> None:
    channel, fake, files = _faked()
    fake.hook = lambda request: error(request, "ERROR", "413-3") if request["type"] == "MOVE_FILE" else None
    with pytest.raises(Exception, match="413-3"):
        await files.upload("/data/big.bin", io.BytesIO(b"012345"))
    delete = channel.last()
    assert delete["type"] == "DELETE_FILE"
    assert STAGING.match(str(delete["path"]))
    assert fake.files == {}


async def test_a_failing_cleanup_keeps_the_original_error() -> None:
    channel, _, files = _faked()

    def refuse(request: JsonObject) -> JsonObject | None:
        if request["type"] == "DELETE_FILE":
            return error(request, "ERROR", "500-43")
        return error(request, "ERROR", "413-2") if request["mode"] == "CREATE" else None

    channel.replies = refuse
    with pytest.raises(Exception, match="413-2"):
        await files.upload("/data/big.bin", io.BytesIO(b"x"))
    assert [request["type"] for request in channel.requests] == ["WRITE_FILE", "DELETE_FILE"]


def test_the_staging_path_is_a_hidden_sibling() -> None:
    assert re.match(r"^/\.top\.upload-[A-Za-z0-9]{8}$", staging_path("/top"))
    assert re.match(r"^/a/b/\.c\.txt\.upload-[A-Za-z0-9]{8}$", staging_path("/a/b/c.txt"))
    assert staging_path("/top") != staging_path("/top")


def test_chunks_cut_files_and_iterables_at_the_size() -> None:
    assert list(chunks(io.BytesIO(b"abcdefg"), 3)) == [b"abc", b"def", b"g"]
    assert list(chunks([b"abcdef"], 3)) == [b"abc", b"def"]
    assert list(chunks(io.BytesIO(b""), 3)) == []


@pytest.mark.usefixtures("small_chunks")
async def test_read_restarts_when_the_version_changes_between_windows() -> None:
    channel, fake, files = _faked()
    fake.put("/f", b"0123456789")
    reads = 0

    def bump(request: JsonObject) -> JsonObject | None:
        nonlocal reads
        if request["type"] == "READ_FILE":
            reads += 1
            if reads == 2:
                fake.put("/f", b"abcdefghij")
        return None

    fake.hook = bump
    assert await files.read("/f") == b"abcdefghij"
    offsets = [request["offset"] for request in channel.requests]
    assert offsets == [0, 4, 0, 4, 8]


@pytest.mark.usefixtures("small_chunks")
async def test_read_gives_up_after_three_restarts() -> None:
    channel, fake, files = _faked()
    fake.put("/f", b"0123456789")

    def always(request: JsonObject) -> JsonObject | None:
        if request["offset"] == 4:
            fake.put("/f", b"0123456789")
        return None

    fake.hook = always
    with pytest.raises(FileChangedError, match="kept changing"):
        await files.read("/f")
    assert [request["offset"] for request in channel.requests].count(0) == 4


async def test_an_empty_window_ends_the_read() -> None:
    channel = RecordingChannel()
    channel.replies = lambda request: ok(request, version="1", content="", encoding="base64", eof=False)
    assert await _files(channel).read("/f") == b""


async def test_a_missing_file_is_not_found() -> None:
    _, _, files = _faked()
    with pytest.raises(NotFoundError):
        await files.read("/missing")


@pytest.mark.usefixtures("small_chunks")
async def test_download_rewinds_a_seekable_target_on_a_restart() -> None:
    _, fake, files = _faked()
    fake.put("/f", b"0123456789")
    changed = False

    def once(request: JsonObject) -> JsonObject | None:
        nonlocal changed
        if request["offset"] == 4 and not changed:
            changed = True
            fake.put("/f", b"abcdefghij")
        return None

    fake.hook = once
    target = io.BytesIO(b"head:")
    target.seek(0, io.SEEK_END)
    await files.download("/f", target)
    assert target.getvalue() == b"head:abcdefghij"


class _Pipe(io.BytesIO):
    def seekable(self) -> bool:
        return False


@pytest.mark.usefixtures("small_chunks")
async def test_download_into_a_stream_that_cannot_seek_fails_on_a_change() -> None:
    _, fake, files = _faked()
    fake.put("/f", b"0123456789")

    def change(request: JsonObject) -> JsonObject | None:
        if request["offset"] == 4:
            fake.put("/f", b"x" * 10)
        return None

    fake.hook = change
    with pytest.raises(FileChangedError, match="cannot seek"):
        await files.download("/f", _Pipe())
    unchanged = _Pipe()
    fake.hook = lambda request: None
    await files.download("/f", unchanged)
    assert unchanged.getvalue() == b"x" * 10
