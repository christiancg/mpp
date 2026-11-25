from __future__ import annotations

import io
from collections.abc import Iterator

import pytest

from mpp import AsyncProject, FileEncoding, FileWriteMode, NotFoundError, Project, ProjectFiles
from mpp._internal.portal import SyncRuntime
from tests.support.fake_files import FakeFiles
from tests.support.recording_channel import RecordingChannel, recording_context


@pytest.fixture
def runtime() -> Iterator[SyncRuntime]:
    started = SyncRuntime()
    yield started
    started.stop(5.0)


@pytest.fixture
def fake() -> FakeFiles:
    return FakeFiles()


@pytest.fixture
def channel(fake: FakeFiles) -> RecordingChannel:
    recording = RecordingChannel()
    recording.replies = fake
    return recording


@pytest.fixture
def files(runtime: SyncRuntime, channel: RecordingChannel) -> ProjectFiles:
    return Project(runtime, AsyncProject(recording_context(channel), "shop")).files()


def test_the_sync_api_writes_and_reads_bytes_and_text(files: ProjectFiles, fake: FakeFiles) -> None:
    binary = bytes(range(256))
    assert files.write("/all.bin", binary).size == 256
    files.write("/note.txt", "a", mode=FileWriteMode.REPLACE)
    files.append("/note.txt", "b")
    assert files.read("/all.bin") == binary
    assert files.read_text("/note.txt") == "ab"
    assert files.read_range("/note.txt", 1, encoding=FileEncoding.UTF8).data == b"b"
    assert fake.files["/note.txt"] == b"ab"
    with pytest.raises(NotFoundError):
        files.read("/missing")


def test_the_sync_api_uploads_downloads_and_lists(
    files: ProjectFiles, channel: RecordingChannel, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr("mpp._async.files.CHUNK_BYTES", 3)
    uploaded = files.upload("/up/data.bin", io.BytesIO(b"abcdefgh"))
    assert (uploaded.path, uploaded.size) == ("/up/data.bin", 8)
    target = io.BytesIO()
    files.download("/up/data.bin", target)
    assert target.getvalue() == b"abcdefgh"
    files.write("/up/other.bin", b"x")
    assert [entry.path for entry in files.list("/up", True, limit=1)] == ["/up/data.bin", "/up/other.bin"]
    assert channel.last()["after"] == "/up/data.bin"


def test_the_sync_api_moves_and_deletes(files: ProjectFiles, fake: FakeFiles) -> None:
    files.write("/a", b"1")
    moved = files.move("/a", "/b", overwrite=True)
    assert (moved.path, list(fake.files)) == ("/b", ["/b"])
    assert files.delete("/b") == 1
    assert files.delete("/b", recursive=True) == 0
