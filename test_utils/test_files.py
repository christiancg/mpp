import base64
import hashlib
import os
import sys
import tempfile
import threading

import base_utils as bu
from base_utils import check, check_code, check_field, check_status, section

HOST = "127.0.0.1"
PORT = int(os.environ.get("FILES_TEST_PORT", "9461"))
ADMIN_USERNAME = "admin"
PROJECT = "files_test_project"
OTHER_PROJECT = "files_other_project"
COLL = "notes"
USER_PASSWORD = "password123"
READER = "files_reader"
WRITER = "files_writer"
COLLECTION_ONLY = "files_coll_only"
MAX_FILE_SIZE = 48 * 1024 * 1024
MAX_ENTRIES = 200
CHUNK = 4 * 1024 * 1024
BIG_SIZE = 40 * 1024 * 1024 + 12345

bu.configure(host=HOST, port=PORT, username=ADMIN_USERNAME, password=bu.PASSWORD)


class Conn(bu.Conn):
    def write(self, path, data, project=PROJECT, **extra) -> dict:
        payload = {"type": "WRITE_FILE", "projectName": project, "path": path}
        if isinstance(data, str):
            payload.update(content=data, encoding="utf8")
        else:
            payload.update(content=base64.b64encode(data).decode())
        payload.update(extra)
        return self.send(payload)

    def read(self, path, project=PROJECT, **extra) -> dict:
        payload = {"type": "READ_FILE", "projectName": project, "path": path}
        payload.update(extra)
        return self.send(payload)

    def read_bytes(self, path) -> bytes:
        reply = self.read(path)
        return base64.b64decode(reply.get("content") or "")

    def stat(self, path, project=PROJECT) -> dict:
        return self.send({"type": "STAT_FILE", "projectName": project, "path": path})

    def list_files(self, path="/", project=PROJECT, **extra) -> dict:
        payload = {"type": "LIST_FILES", "projectName": project, "path": path}
        payload.update(extra)
        return self.send(payload)

    def delete(self, path, recursive=False, project=PROJECT) -> dict:
        return self.send({"type": "DELETE_FILE", "projectName": project, "path": path, "recursive": recursive})

    def move(self, source, target, **extra) -> dict:
        payload = {"type": "MOVE_FILE", "projectName": PROJECT, "from": source, "to": target}
        payload.update(extra)
        return self.send(payload)

    def mkdir(self, path, **extra) -> dict:
        payload = {"type": "MAKE_DIRECTORY", "projectName": PROJECT, "path": path}
        payload.update(extra)
        return self.send(payload)

    def paths(self, path="/", recursive=True) -> list:
        return [entry["path"] for entry in self.list_files(path, recursive=recursive).get("entries") or []]


def admin_conn() -> Conn:
    return Conn.authenticated()


def user_conn(username: str) -> Conn:
    return Conn.authenticated(username, USER_PASSWORD)


def write_config(work_dir: str, **overrides):
    settings = {"filesMaxFileSize": f"{MAX_FILE_SIZE // (1024 * 1024)}Mb", "filesMaxEntriesPerProject": MAX_ENTRIES,
                "filesMaxReadBytes": "4Mb"}
    settings.update(overrides)
    bu.write_config(work_dir, PORT, **settings)


def restart(proc, work_dir: str, log_path: str, **overrides):
    bu.stop_server(proc)
    write_config(work_dir, **overrides)
    return bu.start_server(work_dir, log_path)


def every_byte() -> bytes:
    return bytes(range(256))


def setup_data(conn: Conn):
    section("Setup")
    for project in (PROJECT, OTHER_PROJECT):
        check_status(f"create {project}", conn.send({"type": "CREATE_PROJECT", "projectName": project}), "OK")
    check_status("create a collection", conn.send(
        {"type": "CREATE_COLLECTION", "projectName": PROJECT, "collectionName": COLL}), "OK")


def test_round_trips(conn: Conn):
    section("Binary and text files round trip")
    written = conn.write("/bin/all.bytes", every_byte())
    check_status("write every byte value", written, "OK")
    check_field("the size is 256", written, "size", 256)
    check("the 256 bytes read back", conn.read_bytes("/bin/all.bytes") == every_byte(), "")
    text = "héllo ✓ 日本\n"
    check_status("write UTF-8 text", conn.write("text/hello.txt", text), "OK")
    reply = conn.read("/text/hello.txt", encoding="utf8")
    check("the text reads back as written", reply.get("content") == text, f"got {reply}")
    check_code("binary bytes read as utf8 are refused", conn.read("/bin/all.bytes", encoding="utf8"), "ERROR",
               "400-52")
    window = conn.read("/bin/all.bytes", offset=250, length=100)
    check("a window past the middle stops at the end", base64.b64decode(window["content"]) == bytes(range(250, 256))
          and window.get("eof") is True, f"got {window}")
    past = conn.read("/bin/all.bytes", offset=1000)
    check("an offset past the end answers an empty window", past.get("content") == "" and past.get("eof") is True,
          f"got {past}")


def test_modes_and_versions(conn: Conn):
    section("REPLACE, CREATE, APPEND and ifVersion")
    first = conn.write("/log.txt", "one")
    check_status("create by replacing", first, "OK")
    check_code("CREATE on an existing path", conn.write("/log.txt", "x", mode="CREATE"), "ERROR", "409-20")
    appended = conn.write("/log.txt", "-two", mode="APPEND")
    check_field("APPEND adds to the end", appended, "size", 7)
    check("the version moved", appended.get("version") != first.get("version"), "")
    check_code("a stale ifVersion is refused", conn.write("/log.txt", "x", ifVersion=first["version"]), "ERROR",
               "409-23")
    check_code("ifVersion 0 on an existing file is refused", conn.write("/log.txt", "x", ifVersion="0"), "ERROR",
               "409-23")
    check_status("the current ifVersion applies", conn.write("/log.txt", "three", ifVersion=appended["version"]), "OK")
    check_status("APPEND creates a missing file", conn.write("/new.log", "a", mode="APPEND"), "OK")
    check_code("no parent with createParents false", conn.write("/no/parent", "x", createParents=False), "NOT_FOUND",
               "404-14")


def test_folders_listing_moves_and_deletes(conn: Conn):
    section("Folders, listing, moves and deletes")
    made = conn.mkdir("/site/assets/img")
    check_field("MAKE_DIRECTORY creates the missing levels", made, "created", 3)
    check_field("an existing folder creates nothing", conn.mkdir("/site/assets"), "created", 0)
    check_code("without recursive an existing folder is refused", conn.mkdir("/site", recursive=False), "ERROR",
               "409-20")
    for name in ("index.html", "app.js", "style.css"):
        check_status(f"write {name}", conn.write(f"/site/{name}", f"<{name}>"), "OK")
    stat = conn.stat("/site")
    check("STAT_FILE of a folder", stat.get("kind") == "DIRECTORY" and stat.get("modifiedAt", 0) > 0, f"got {stat}")
    check_code("STAT_FILE of nothing", conn.stat("/nothing"), "NOT_FOUND", "404-14")
    first = conn.list_files("/site", limit=2)
    second = conn.list_files("/site", limit=2, after=first.get("next"))
    names = [entry["path"] for entry in first["entries"] + second["entries"]]
    check("the folder lists in path order over pages", names == ["/site/app.js", "/site/assets",
                                                                  "/site/index.html", "/site/style.css"],
          f"got {names}")
    check("recursive listing reaches the deepest folder", "/site/assets/img" in conn.paths("/site"), "")
    check_code("listing a file is refused", conn.list_files("/site/app.js"), "ERROR", "409-21")
    check_code("a file where a folder is", conn.write("/site/assets", "x"), "ERROR", "409-21")
    check_code("a name differing only by case", conn.write("/SITE/x", "x"), "ERROR", "409-11")
    moved = conn.move("/site", "/www")
    check_field("the folder moves with everything in it", moved, "moved", 6)
    check("the old path is gone", conn.stat("/site").get("errorCode") == "404-14", "")
    check_code("moving onto an existing file needs overwrite", conn.move("/log.txt", "/new.log"), "ERROR", "409-20")
    check_status("with overwrite it replaces it", conn.move("/log.txt", "/new.log", overwrite=True), "OK")
    check_code("a folder can't move into itself", conn.move("/www", "/www/inner"), "ERROR", "400-51")
    check_code("a non-empty folder needs recursive", conn.delete("/www"), "ERROR", "409-22")
    check_field("recursive delete removes the subtree", conn.delete("/www", recursive=True), "deleted", 6)
    check_field("deleting nothing answers OK", conn.delete("/www", recursive=True), "deleted", 0)


def test_path_refusals(conn: Conn):
    section("Paths that never reach the disk")
    for path in ("/../escape", "/a%b", "/CON", "/name.", "/ space", "/café", "/a" * 33, "/" + "x" * 256):
        check_code(f"refuse {path[:40]!r}", conn.write(path, "x"), "ERROR", "400-51")
    check_code("bad base64", conn.send({"type": "WRITE_FILE", "projectName": PROJECT, "path": "/b",
                                         "content": "@@@"}), "ERROR", "400-52")
    check_code("an unknown mode", conn.write("/b", "x", mode="TRUNCATE"), "ERROR", "400-52")
    check_code("the admin project has no files", conn.write("/x", "x", project="admin"), "ERROR", "400-1")
    check_code("an unknown project", conn.write("/x", "x", project="nosuchproject"), "NOT_FOUND", "404-4")


def test_quotas(conn: Conn):
    section("File size and entry quotas")
    filled = 0
    reply = {}
    for i in range(MAX_ENTRIES + 1):
        reply = conn.write(f"/many/f{i:04d}", "")
        if reply.get("status") != "OK":
            break
        filled += 1
    check_code("the entry quota refuses one more", reply, "ERROR", "413-3")
    check("the project kept its earlier files", filled > 0, f"filled {filled}")
    check_status("deleting frees the quota", conn.delete("/many", recursive=True), "OK")


def test_permissions(admin: Conn):
    section("Project grants gate the files, collection grants don't")
    users = {READER: {"projectPermissions": {PROJECT: "READ"}},
             WRITER: {"projectPermissions": {PROJECT: "READ_WRITE"}},
             COLLECTION_ONLY: {"collectionPermissions": {f"{PROJECT}|{COLL}": "READ_WRITE"}}}
    for username, grants in users.items():
        payload = {"type": "CREATE_USER", "username": username, "password": USER_PASSWORD, "admin": False}
        payload.update(grants)
        check_status(f"create {username}", admin.send(payload), "OK")
    check_status("seed a file", admin.write("/shared.txt", "shared"), "OK")
    with user_conn(READER) as reader:
        check_status("READ reads", reader.read("/shared.txt"), "OK")
        check_status("READ lists", reader.list_files(), "OK")
        check_code("READ can't write", reader.write("/r.txt", "x"), "FORBIDDEN", "403-1")
        check_code("nor reach another project", reader.list_files(project=OTHER_PROJECT), "FORBIDDEN", "403-1")
    with user_conn(WRITER) as writer:
        check_status("READ_WRITE writes", writer.write("/w.txt", "x"), "OK")
        check_status("and deletes", writer.delete("/w.txt"), "OK")
    with user_conn(COLLECTION_ONLY) as limited:
        check_code("a collection grant reads no file", limited.read("/shared.txt"), "FORBIDDEN", "403-1")
        check_code("nor writes one", limited.write("/c.txt", "x"), "FORBIDDEN", "403-1")


def test_transactions(conn: Conn):
    section("Reads work inside a transaction, writes are refused")
    check_status("start a transaction", conn.send({"type": "START_TRANSACTION"}), "OK")
    check_status("READ_FILE inside it", conn.read("/shared.txt"), "OK")
    check_code("WRITE_FILE inside it", conn.write("/tx.txt", "x"), "ERROR", "409-6")
    check_status("roll back", conn.send({"type": "ROLLBACK_TRANSACTION"}), "OK")


def upload(conn: Conn, path: str, data: bytes) -> dict:
    staging = path + ".upload-test"
    for offset in range(0, len(data), CHUNK):
        mode = "CREATE" if offset == 0 else "APPEND"
        reply = conn.write(staging, data[offset:offset + CHUNK], mode=mode)
        if reply.get("status") != "OK":
            return reply
    return conn.move(staging, path, overwrite=True)


def download(conn: Conn, path: str) -> bytes:
    parts = []
    offset = 0
    while True:
        window = conn.read(path, offset=offset)
        data = base64.b64decode(window.get("content") or "")
        parts.append(data)
        offset += len(data)
        if window.get("eof") or not data:
            return b"".join(parts)


def test_large_files(conn: Conn):
    section("A 40 MB file goes up in chunks and comes back window by window")
    payload = os.urandom(BIG_SIZE)
    check_status("upload it", upload(conn, "/big/blob.bin", payload), "OK")
    check_field("its size", conn.stat("/big/blob.bin"), "size", BIG_SIZE)
    back = download(conn, "/big/blob.bin")
    check("the SHA-256 matches", hashlib.sha256(back).hexdigest() == hashlib.sha256(payload).hexdigest(),
          f"{len(back)} bytes back")
    check_code("an append past filesMaxFileSize", conn.write("/big/blob.bin", b"x" * (MAX_FILE_SIZE - BIG_SIZE + 1),
                                                             mode="APPEND"), "ERROR", "413-2")
    window = conn.read("/big/blob.bin", length=64 * 1024 * 1024)
    check("one window is capped by filesMaxReadBytes", len(base64.b64decode(window["content"])) == 4 * 1024 * 1024
          and window.get("eof") is False, "")
    return hashlib.sha256(payload).hexdigest()


def test_stats(conn: Conn):
    section("GET_PROJECT_STATS counts the files")
    stats = conn.send({"type": "GET_PROJECT_STATS"})
    project = next((entry for entry in stats.get("stats", {}).get("projects", []) if entry.get("name") == PROJECT),
                   {})
    files = project.get("files") or {}
    check("entries and bytes are reported", files.get("entries", 0) > 0 and files.get("bytes", 0) > BIG_SIZE,
          f"got {files}")
    with user_conn(COLLECTION_ONLY) as limited:
        seen = limited.send({"type": "GET_PROJECT_STATS"}).get("stats", {}).get("projects", [])
        listed = next((entry for entry in seen if entry.get("name") == PROJECT), None)
        check("a collection-only user sees the project", listed is not None, f"got {seen}")
        check("but not its files", listed is not None and "files" not in listed, f"got {listed}")


def test_survives_restart(conn: Conn, big_hash: str):
    section("Files survive a graceful restart")
    check("the binary file", conn.read_bytes("/bin/all.bytes") == every_byte(), "")
    check("the large file", hashlib.sha256(download(conn, "/big/blob.bin")).hexdigest() == big_hash, "")
    check("the folders", "/text" in conn.paths(), "")


def test_kill_during_appends(work_dir: str, log_path: str):
    section("A kill -9 during appends leaves whole appends only")
    proc = bu.start_server(work_dir, log_path)
    chunk = b"0123456789abcdef" * 4096
    with admin_conn() as conn:
        check_status("start the log", conn.write("/kill/appends.bin", b""), "OK")
    counts = {"acknowledged": 0}

    def append_until_killed():
        with admin_conn() as appender:
            while appender.write("/kill/appends.bin", chunk, mode="APPEND").get("status") == "OK":
                counts["acknowledged"] += 1

    appender_thread = threading.Thread(target=append_until_killed, daemon=True)
    appender_thread.start()
    bu.wait_until(lambda: counts["acknowledged"] >= 20, timeout_s=30)
    bu.kill_pid(proc.pid)
    proc.wait(timeout=30)
    appender_thread.join(timeout=30)
    proc = bu.start_server(work_dir, log_path)
    with admin_conn() as conn:
        size = conn.stat("/kill/appends.bin").get("size", -1)
        acknowledged = counts["acknowledged"]
        check("every acknowledged append survived, and no partial one",
              size in (acknowledged * len(chunk), (acknowledged + 1) * len(chunk)),
              f"size {size}, acknowledged {acknowledged}")
        on_disk = os.path.getsize(os.path.join(work_dir, "files", PROJECT, "kill", "appends.bin"))
        check("the file on disk matches the manifest", on_disk == size, f"{on_disk} on disk")
    return proc


def test_adoption_and_drop(work_dir: str, log_path: str, proc):
    section("Copied-in files are adopted, symbolic links refused, a drop removes the tree")
    bu.stop_server(proc)
    tree = os.path.join(work_dir, "files", PROJECT)
    os.makedirs(os.path.join(tree, "copied", "in"), exist_ok=True)
    with open(os.path.join(tree, "copied", "in", "x.txt"), "w") as handle:
        handle.write("copied")
    if hasattr(os, "symlink") and os.name != "nt":
        os.symlink(work_dir, os.path.join(tree, "link"))
    proc = bu.start_server(work_dir, log_path)
    with admin_conn() as conn:
        check_field("the copied file is adopted", conn.stat("/copied/in/x.txt"), "size", 6)
        if os.name != "nt":
            check_code("the link is not", conn.stat("/link"), "NOT_FOUND", "404-14")
            os.remove(os.path.join(tree, "link"))
        check("no document file in the files folder", not any(
            name.endswith(".dat") for _, _, names in os.walk(os.path.join(work_dir, "files")) for name in names), "")
        check("no project file in the data folder", not os.path.exists(os.path.join(work_dir, "db", PROJECT, "bin")),
              "")
        check_status("drop the project", conn.send({"type": "DROP_PROJECT", "projectName": PROJECT}), "OK")
        check("its tree is gone", not os.path.exists(tree), "")
        check("its manifest is gone", not os.path.exists(os.path.join(work_dir, "files", ".mpp", PROJECT)), "")
        check_status("create it again", conn.send({"type": "CREATE_PROJECT", "projectName": PROJECT}), "OK")
        check("the new project starts with no files", conn.paths() == [], f"got {conn.paths()}")
    return proc


def main():
    bu.banner("Project files test suite")
    bu.require_jar()
    if bu.port_open():
        print(f"port {PORT} is already in use", file=sys.stderr)
        sys.exit(1)
    work_dir = tempfile.mkdtemp(prefix="mpp-files-test-")
    log_path = os.path.join(work_dir, "server.out")
    proc = None
    try:
        print(f"work dir: {work_dir}")
        write_config(work_dir)
        proc = bu.start_server(work_dir, log_path)
        with admin_conn() as conn:
            setup_data(conn)
            test_round_trips(conn)
            test_modes_and_versions(conn)
            test_folders_listing_moves_and_deletes(conn)
            test_path_refusals(conn)
            test_quotas(conn)
            test_permissions(conn)
            test_transactions(conn)
            big_hash = test_large_files(conn)
            test_stats(conn)

        proc = restart(proc, work_dir, log_path)
        with admin_conn() as conn:
            test_survives_restart(conn, big_hash)

        bu.stop_server(proc)
        proc = test_kill_during_appends(work_dir, log_path)
        proc = test_adoption_and_drop(work_dir, log_path, proc)

        bu.stop_server(proc)
        proc = None
        section("A filesPath inside filePath refuses to start")
        write_config(work_dir, filesPath="db/files")
        exit_code = bu.run_until_exit(work_dir, log_path)
        check("the server exits with an error", exit_code not in (None, 0), f"exit code {exit_code}")
    except Exception as exc:  # noqa: BLE001
        print(f"\nunexpected failure: {exc}", file=sys.stderr)
        bu.dump_log(log_path)
        bu.record_failure()
    finally:
        bu.stop_server(proc)

    bu.summary(on_failure=lambda: bu.dump_log(log_path))


if __name__ == "__main__":
    main()
