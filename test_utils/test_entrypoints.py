import http.client
import json
import os
import socket
import ssl
import sys
import tempfile
import threading
import time

import base_utils as bu
from base_utils import check, check_code, check_status, section

HOST = "127.0.0.1"
PORT = int(os.environ.get("ENTRYPOINT_TEST_PORT", "8996"))
HTTP_PORT = int(os.environ.get("ENTRYPOINT_HTTP_PORT", "8997"))
ADMIN_USERNAME = "admin"
PROJECT = "ep_test_project"
COLL = "orders"
USER_PASSWORD = "password123"
LIMITED = "ep_limited"
REVOKED = "ep_revoked"
READER = "ep_reader"
RUNNER = "ep_runner"
MAX_BODY_BYTES = 4096
MAX_CONNECTIONS = 4
IDLE_TIMEOUT_MS = 500
READ_TIMEOUT_MS = 2000
BASE = f"/{PROJECT}"

bu.configure(host=HOST, port=PORT, username=ADMIN_USERNAME, password=bu.PASSWORD)


class Conn(bu.Conn):
    def save_entrypoint(self, name, method, path, project=PROJECT, **extra) -> dict:
        payload = {"type": "SAVE_ENTRYPOINT", "projectName": project, "name": name, "method": method, "path": path}
        payload.update(extra)
        return self.send(payload)

    def expose(self, name, method, path, script, **extra) -> dict:
        return self.save_entrypoint(name, method, path, procedure={"name": name, "script": script}, **extra)

    def delete_entrypoint(self, name, project=PROJECT) -> dict:
        return self.send({"type": "DELETE_ENTRYPOINT", "projectName": project, "name": name})

    def list_entrypoints(self, project=PROJECT) -> dict:
        return self.send({"type": "LIST_ENTRYPOINTS", "projectName": project})

    def entrypoint(self, name) -> dict:
        listed = self.list_entrypoints().get("entrypoints") or []
        return next((entry for entry in listed if entry.get("name") == name), {})


def admin_conn() -> Conn:
    return Conn.authenticated()


def user_conn(username: str) -> Conn:
    return Conn.authenticated(username, USER_PASSWORD)


class Reply:
    def __init__(self, status: int, headers: dict, body: bytes):
        self.status = status
        self.headers = {key.lower(): value for key, value in headers.items()}
        self.body = body

    def json(self):
        return json.loads(self.body.decode("utf-8"))

    def text(self) -> str:
        return self.body.decode("utf-8")


def request(method: str, path: str, body=None, headers=None, tls: bool = False) -> Reply:
    connection = https_connection() if tls else http.client.HTTPConnection(HOST, HTTP_PORT, timeout=15)
    try:
        payload = json.dumps(body).encode("utf-8") if isinstance(body, (dict, list)) else body
        all_headers = dict(headers or {})
        if isinstance(body, (dict, list)):
            all_headers.setdefault("Content-Type", "application/json")
        connection.request(method, path, body=payload, headers=all_headers)
        response = connection.getresponse()
        return Reply(response.status, dict(response.getheaders()), response.read())
    finally:
        connection.close()


def unverified_context() -> ssl.SSLContext:
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    return context


def https_connection() -> http.client.HTTPSConnection:
    return http.client.HTTPSConnection(HOST, HTTP_PORT, timeout=15, context=unverified_context())


def raw_socket() -> socket.socket:
    return socket.create_connection((HOST, HTTP_PORT), timeout=10)


def read_until_closed(sock: socket.socket, timeout: float = 10.0) -> bytes:
    sock.settimeout(timeout)
    chunks = []
    try:
        while True:
            chunk = sock.recv(65536)
            if not chunk:
                break
            chunks.append(chunk)
    except (ConnectionResetError, socket.timeout):
        pass
    return b"".join(chunks)


def http_port_open() -> bool:
    try:
        with socket.create_connection((HOST, HTTP_PORT), timeout=1):
            return True
    except OSError:
        return False


def write_config(work_dir: str, **overrides):
    settings = {
        "httpPort": HTTP_PORT, "httpMaxRequestBytes": f"{MAX_BODY_BYTES // 1024}Kb",
        "httpMaxConnections": MAX_CONNECTIONS, "httpIdleTimeoutMs": IDLE_TIMEOUT_MS,
        "httpReadTimeoutMs": READ_TIMEOUT_MS, "scriptsEnabled": True, "scriptInstructionBudget": 2_000_000_000,
        "scriptTimeoutMs": 20000, "maxConcurrentScriptsPerUser": 1, "maxConcurrentScriptsPerProject": 0,
        "scriptRunHistoryEnabled": True, "scriptRunHistoryKinds": "CALL_PROCEDURE",
    }
    settings.update(overrides)
    bu.write_config(work_dir, PORT, **settings)


def setup_data(conn: Conn):
    section("Setup")
    check_status("create the project", conn.send({"type": "CREATE_PROJECT", "projectName": PROJECT}), "OK")
    check_status("create the collection",
                 conn.send({"type": "CREATE_COLLECTION", "projectName": PROJECT, "collectionName": COLL}), "OK")


def test_get_with_path_params_query_and_headers(conn: Conn):
    section("A GET hands the procedure its path parameters, query and headers")
    check_status("expose an echo", conn.expose("echo", "GET", "/echo/{id}",
                                               'import args from "args";\nreturn args;'), "OK")
    reply = request("GET", f"{BASE}/echo/a%20b?tag=x&tag=y&flag", headers={"X-Request-Id": "r-9"})
    check("it answers 200", reply.status == 200, f"got {reply.status} {reply.body!r}")
    args = reply.json()
    check("the entrypoint and method are named", args.get("entrypoint") == "echo" and args.get("method") == "GET",
          f"got {args}")
    check("the path parameter is decoded", args.get("pathParams") == {"id": "a b"}, f"got {args}")
    check("the path is the part after the project", args.get("path") == "/echo/a%20b", f"got {args}")
    check("a repeated query key is an array", args.get("query") == {"tag": ["x", "y"], "flag": ""}, f"got {args}")
    check("headers are lower-cased", args.get("headers", {}).get("x-request-id") == "r-9", f"got {args}")
    check("no body is null", args.get("body") is None, f"got {args}")
    check("the remote address is there", args.get("remoteAddress") == "127.0.0.1", f"got {args}")


def test_post_json_body_saves_document_and_returns_201_with_location(conn: Conn):
    section("A POST saves its JSON body and answers through an envelope")
    script = ('import db from "db";\nimport args from "args";\n'
              f'const saved = db.save(db.name, "{COLL}", args.body);\n'
              'return {status: 201, headers: {Location: `/' + PROJECT + '/orders/${saved._id}`}, body: saved};')
    check_status("expose createOrder", conn.expose("createOrder", "POST", "/orders", script), "OK")
    reply = request("POST", f"{BASE}/orders", body={"_id": "o1", "qty": 2})
    check("it answers 201", reply.status == 201, f"got {reply.status} {reply.body!r}")
    check("with the Location header", reply.headers.get("location") == f"/{PROJECT}/orders/o1", f"got {reply.headers}")
    check("and the saved document as JSON", reply.json().get("qty") == 2
          and reply.headers.get("content-type") == "application/json", f"got {reply.body!r}")
    found = conn.send({"type": "FIND_BY_ID", "projectName": PROJECT, "collectionName": COLL, "_id": "o1"})
    check("the document is in the collection", found.get("object", {}).get("qty") == 2, f"got {found}")


def test_query_method_carries_a_body(conn: Conn):
    section("QUERY is routed and hands the procedure its body")
    script = ('import db from "db";\nimport args from "args";\n'
              f'return db.findById(db.name, "{COLL}", args.body.id);')
    check_status("expose a QUERY search", conn.expose("searchOrders", "query", "/orders", script), "OK")
    check("it is stored upper-case", conn.entrypoint("searchOrders").get("method") == "QUERY",
          f"got {conn.entrypoint('searchOrders')}")
    reply = request("QUERY", f"{BASE}/orders", body={"id": "o1"})
    check("a QUERY with a JSON body is served", reply.status == 200 and reply.json().get("_id") == "o1",
          f"got {reply.status} {reply.body!r}")
    reply = request("PUT", f"{BASE}/orders")
    check("Allow lists QUERY beside POST", reply.status == 405 and reply.headers.get("allow") == "POST, QUERY",
          f"got {reply.status} {reply.headers}")


def test_plain_returns(conn: Conn):
    section("A plain return value is the body")
    check_status("expose an object", conn.expose("plainObject", "GET", "/plain/object", "return {a: 1};"), "OK")
    check_status("expose a string", conn.expose("plainText", "GET", "/plain/text", "return 'hi';"), "OK")
    check_status("expose a null", conn.expose("plainNull", "GET", "/plain/null", "return null;"), "OK")
    check_status("expose a status field", conn.expose("statusField", "GET", "/plain/status",
                                                      "return {status: 'active'};"), "OK")
    reply = request("GET", f"{BASE}/plain/object")
    check("an object is 200 JSON", reply.status == 200 and reply.json() == {"a": 1}, f"got {reply.body!r}")
    reply = request("GET", f"{BASE}/plain/text")
    check("a string is text/plain", reply.text() == "hi" and reply.headers.get("content-type", "").startswith(
        "text/plain"), f"got {reply.headers} {reply.body!r}")
    reply = request("GET", f"{BASE}/plain/null")
    check("null is an empty 200", reply.status == 200 and reply.body == b"", f"got {reply.status} {reply.body!r}")
    reply = request("GET", f"{BASE}/plain/status")
    check("a string status is data, not an envelope", reply.status == 200 and reply.json() == {"status": "active"},
          f"got {reply.status} {reply.body!r}")


def test_failures_are_500(conn: Conn, log_path: str):
    section("A thrown error or a timeout is 500 and never leaks the message")
    check_status("expose a thrower", conn.expose("thrower", "GET", "/throw", "throw new Error('very secret');"),
                 "OK")
    check_status("expose a slow one", conn.expose("slowpoke", "GET", "/slow", "while (true) {}", timeoutMs=300), "OK")
    reply = request("GET", f"{BASE}/throw")
    check("a throw is 500", reply.status == 500, f"got {reply.status}")
    body = reply.json()
    check("the body carries only a run id", set(body) == {"error", "runId"} and "secret" not in reply.text(),
          f"got {body}")
    log = bu.read_log(log_path)
    check("the server log has the message and the run id", any(
        "very secret" in line and body.get("runId", "?") in line for line in log.splitlines()), "no log line")
    started = time.time()
    reply = request("GET", f"{BASE}/slow")
    check("a timeout is 500", reply.status == 500, f"got {reply.status}")
    check("within the entrypoint's timeout", time.time() - started < 10, f"took {time.time() - started:.1f}s")


def test_routing(conn: Conn):
    section("Routing: 404, 405 and most specific first")
    check_status("expose byId", conn.expose("byId", "GET", "/items/{id}", 'import args from "args";\n'
                                            'return "item " + args.pathParams.id;'), "OK")
    check_status("expose latest", conn.expose("latest", "GET", "/items/latest", "return 'latest';"), "OK")
    check_status("expose a delete", conn.expose("removeItem", "DELETE", "/items/{id}", "return null;"), "OK")
    check_status("store an item whose id is latest", conn.send({
        "type": "SAVE", "projectName": PROJECT, "collectionName": COLL, "object": {"_id": "latest"}}), "OK")
    check("the literal route wins even for a stored id equal to it",
          request("GET", f"{BASE}/items/latest").text() == "latest", "")
    check("other values reach the parameter route", request("GET", f"{BASE}/items/i9").text() == "item i9", "")
    check("an unknown project is 404", request("GET", "/no_such_project/items/x").status == 404, "")
    check("an unknown path is 404", request("GET", f"{BASE}/nothing").status == 404, "")
    reply = request("PUT", f"{BASE}/items/i9")
    check("a wrong method is 405 with Allow", reply.status == 405 and reply.headers.get("allow") == "DELETE, GET",
          f"got {reply.status} {reply.headers}")
    check("HEAD is not routed", request("HEAD", f"{BASE}/items/i9").status == 405, "")
    check_code("the same method and shape is 409-19",
               conn.expose("byOtherName", "GET", "/items/{other}", "return 1;"), "ERROR", "409-19")


def test_inline_procedure_and_references(conn: Conn):
    section("An inline procedure is an ordinary procedure")
    check_status("expose with an inline procedure", conn.expose("shared", "GET", "/shared", "return 41 + 1;"), "OK")
    listed = conn.send({"type": "LIST_PROCEDURES", "projectName": PROJECT}).get("procedures") or []
    check("it is listed as a procedure", any(entry.get("name") == "shared" for entry in listed), f"got {listed}")
    called = conn.send({"type": "CALL_PROCEDURE", "projectName": PROJECT, "procedureName": "shared"})
    check("CALL_PROCEDURE runs it", called.get("result") == 42, f"got {called}")
    check_status("a second entrypoint reuses it by name",
                 conn.save_entrypoint("sharedToo", "GET", "/shared/too", procedureName="shared"), "OK")
    check_code("deleting the procedure is refused while an entrypoint uses it",
               conn.send({"type": "DELETE_PROCEDURE", "projectName": PROJECT, "name": "shared"}), "ERROR", "400-46")
    check_status("deleting the entrypoints", conn.delete_entrypoint("shared"), "OK")
    check_status("deleting the other entrypoint", conn.delete_entrypoint("sharedToo"), "OK")
    check("the procedure stays", conn.send({"type": "CALL_PROCEDURE", "projectName": PROJECT,
                                            "procedureName": "shared"}).get("result") == 42, "")
    check("the route is gone", request("GET", f"{BASE}/shared").status == 404, "")
    entry = conn.entrypoint("echo")
    check("a listing carries the url", entry.get("url") == f"/{PROJECT}/echo/{{id}}", f"got {entry}")


def test_disabled_entrypoint(conn: Conn):
    section("A disabled entrypoint is 404 until it is enabled again")
    check_status("save it disabled", conn.save_entrypoint("toggle", "GET", "/toggle", procedureName="plainText",
                                                          enabled=False), "OK")
    check("disabled is 404", request("GET", f"{BASE}/toggle").status == 404, "")
    check_status("enable it", conn.save_entrypoint("toggle", "GET", "/toggle", procedureName="plainText"), "OK")
    check("enabled serves", request("GET", f"{BASE}/toggle").text() == "hi", "")


def test_runs_as_definer(admin: Conn):
    section("The procedure runs as its definer and is capped by the definer's grants")
    check_status("create a definer with MANAGE and READ", admin.send({
        "type": "CREATE_USER", "username": LIMITED, "password": USER_PASSWORD, "admin": False,
        "projectPermissions": {PROJECT: "READ"}, "scriptPermissions": {PROJECT: "MANAGE"}}), "OK")
    with user_conn(LIMITED) as definer:
        check_status("the definer exposes a writer", definer.expose(
            "limitedWrite", "POST", "/limited", f'import db from "db";\nreturn db.save(db.name, "{COLL}", {{}});'), "OK")
        check_status("and a reader", definer.expose(
            "limitedRead", "GET", "/limited", f'import db from "db";\nreturn db.findById(db.name, "{COLL}", "o1");'),
            "OK")
    check("a write the definer may not make fails as 500", request("POST", f"{BASE}/limited").status == 500, "")
    reply = request("GET", f"{BASE}/limited")
    check("a read it may make succeeds", reply.status == 200 and reply.json().get("_id") == "o1", f"got {reply.body!r}")
    check_status("revoke the definer's script grant", admin.send({
        "type": "CHANGE_PERMISSIONS", "username": LIMITED, "admin": False, "globalPermissions": [],
        "projectPermissions": {PROJECT: "READ"}, "collectionPermissions": {}, "scriptPermissions": {}}), "OK")
    stored = admin.entrypoint("limitedRead")
    check("revoking disables its entrypoints", stored.get("enabled") is False, f"got {stored}")
    check("with the reason recorded", "script RUN" in str(stored.get("disabledReason")), f"got {stored}")
    check("a disabled one is 404", request("GET", f"{BASE}/limited").status == 404, "")


def test_framing_refusals():
    section("Bodies over the cap, chunked bodies and malformed JSON")
    reply = request("POST", f"{BASE}/orders", body=b"x" * (MAX_BODY_BYTES + 1),
                    headers={"Content-Type": "text/plain"})
    check("over the cap is 413", reply.status == 413, f"got {reply.status}")
    with raw_socket() as sock:
        sock.sendall(f"POST {BASE}/orders HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n".encode())
        check("chunked is 411", read_until_closed(sock).startswith(b"HTTP/1.1 411"), "")
    reply = request("POST", f"{BASE}/orders", body=b"{bad", headers={"Content-Type": "application/json"})
    check("malformed JSON is 400", reply.status == 400, f"got {reply.status}")


def test_refused_admission_never_reads_the_body(conn: Conn):
    section("A request refused a script slot is answered without reading its body")
    check_status("expose a slow handler", conn.expose(
        "busy", "GET", "/busy", "const end = Date.now() + 2000; while (Date.now() < end) {} return 'done';"), "OK")
    results = {}
    worker = threading.Thread(target=lambda: results.update(slow=request("GET", f"{BASE}/busy")))
    worker.start()
    time.sleep(0.5)
    with raw_socket() as sock:
        sock.sendall((f"POST {BASE}/orders HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\n"
                      f"Content-Length: {MAX_BODY_BYTES}\r\n\r\n").encode())
        head = read_until_closed(sock).decode("latin-1")
    worker.join(30)
    check("the second request is 503", head.startswith("HTTP/1.1 503"), f"got {head[:200]!r}")
    check("with Retry-After", "Retry-After: 1" in head, f"got {head[:300]!r}")
    check("and the connection closes", "Connection: close" in head, f"got {head[:300]!r}")
    check("the slow one still finishes", results.get("slow") is not None and results["slow"].text() == "done", "")


def test_keep_alive_and_idle_close():
    section("Keep-alive serves several requests, and an idle connection closes")
    with raw_socket() as sock:
        sock.sendall(f"GET {BASE}/plain/text HTTP/1.1\r\nHost: x\r\n\r\nGET {BASE}/plain/text HTTP/1.1\r\n"
                     f"Host: x\r\n\r\n".encode())
        received = read_until_closed(sock, timeout=5)
    check("two responses on one connection", received.count(b"HTTP/1.1 200") == 2, f"got {received!r}")
    check("kept alive until idle", b"Connection: keep-alive" in received, f"got {received!r}")


def test_connection_cap_is_503():
    section("Past httpMaxConnections a connection is answered 503")
    holders = [raw_socket() for _ in range(MAX_CONNECTIONS)]
    try:
        time.sleep(0.3)
        with raw_socket() as extra:
            reply = read_until_closed(extra, timeout=5)
        check("the extra connection gets 503", reply.startswith(b"HTTP/1.1 503"), f"got {reply!r}")
    finally:
        for holder in holders:
            holder.close()


def test_auth_of_the_operations(admin: Conn):
    section("Saving needs script MANAGE, listing needs READ")
    check_status("create a reader", admin.send({
        "type": "CREATE_USER", "username": READER, "password": USER_PASSWORD, "admin": False,
        "projectPermissions": {PROJECT: "READ"}}), "OK")
    check_status("create a runner", admin.send({
        "type": "CREATE_USER", "username": RUNNER, "password": USER_PASSWORD, "admin": False,
        "projectPermissions": {PROJECT: "READ_WRITE"}, "scriptPermissions": {PROJECT: "RUN"}}), "OK")
    with user_conn(RUNNER) as runner:
        check_code("a RUN-only user cannot save", runner.expose("nope", "GET", "/nope", "return 1;"),
                   "FORBIDDEN", "403-1")
        check_code("nor delete", runner.delete_entrypoint("echo"), "FORBIDDEN", "403-1")
    with user_conn(READER) as reader:
        check_status("a READ user can list", reader.list_entrypoints(), "OK")


def test_history_rows_when_kind_enabled(conn: Conn):
    section("An entrypoint run is recorded when ENTRYPOINT is a history kind")
    request("GET", f"{BASE}/plain/text")

    def rows():
        response = conn.send({"type": "AGGREGATE", "projectName": PROJECT, "collectionName": "script_runs",
                              "aggregationSteps": [{"type": "SORT", "fieldName": "startedAt", "ascending": False}]})
        return [row for row in (response.get("results") or []) if row.get("kind") == "ENTRYPOINT"]

    found = bu.wait_until(rows, 20, 0.3) or []
    check("a row is written", len(found) >= 1, "no ENTRYPOINT row")
    if found:
        check("naming the entrypoint and its procedure",
              found[0].get("name") == "plainText" and found[0].get("procedure") == "plainText", f"got {found[0]}")


def test_entrypoints_survive_restart():
    section("Entrypoints are routed again after a restart")
    check("a saved entrypoint answers", request("GET", f"{BASE}/plain/text").text() == "hi", "")


def test_https():
    section("With a TLS config the listener speaks HTTPS")
    reply = request("GET", f"{BASE}/plain/text", tls=True)
    check("an entrypoint is served over HTTPS", reply.status == 200 and reply.text() == "hi",
          f"got {reply.status} {reply.body!r}")
    with raw_socket() as sock:
        sock.sendall(f"GET {BASE}/plain/text HTTP/1.1\r\nHost: x\r\n\r\n".encode())
        check("plain HTTP on the TLS port gets no HTTP reply", not read_until_closed(sock).startswith(b"HTTP/"), "")
    raw = socket.create_connection((HOST, HTTP_PORT), timeout=10)
    with unverified_context().wrap_socket(raw, server_hostname="localhost") as sock:
        sock.sendall(f"GET {BASE}/plain/text HTTP/1.1\r\nHost: x\r\n\r\nGET {BASE}/plain/text HTTP/1.1\r\n"
                     f"Host: x\r\n\r\n".encode())
        received = read_until_closed(sock, timeout=5)
    check("keep-alive works over HTTPS", received.count(b"HTTP/1.1 200") == 2, f"got {received!r}")


def tls_admin_conn() -> Conn:
    raw = socket.create_connection((HOST, PORT), timeout=10)
    conn = Conn(sock=unverified_context().wrap_socket(raw, server_hostname="localhost"))
    conn.authenticate()
    return conn


def restart(proc, work_dir: str, log_path: str, **overrides):
    bu.stop_server(proc)
    write_config(work_dir, **overrides)
    return bu.start_server(work_dir, log_path)


def main():
    bu.banner("HTTP entrypoints test suite")
    bu.require_jar()
    if bu.port_open() or http_port_open():
        print(f"port {PORT} or {HTTP_PORT} is already in use", file=sys.stderr)
        sys.exit(1)
    work_dir = tempfile.mkdtemp(prefix="mpp-entrypoint-test-")
    log_path = os.path.join(work_dir, "server.out")
    proc = None
    try:
        print(f"work dir: {work_dir}")
        write_config(work_dir, httpPort=0)
        proc = bu.start_server(work_dir, log_path)
        section("httpPort=0 turns the listener off")
        check("the HTTP port is closed", not http_port_open(), "")

        proc = restart(proc, work_dir, log_path)
        with admin_conn() as conn:
            setup_data(conn)
            test_get_with_path_params_query_and_headers(conn)
            test_post_json_body_saves_document_and_returns_201_with_location(conn)
            test_query_method_carries_a_body(conn)
            test_plain_returns(conn)
            test_failures_are_500(conn, log_path)
            test_routing(conn)
            test_inline_procedure_and_references(conn)
            test_disabled_entrypoint(conn)
            test_runs_as_definer(conn)
            test_framing_refusals()
            test_refused_admission_never_reads_the_body(conn)
            test_keep_alive_and_idle_close()
            test_connection_cap_is_503()
            test_auth_of_the_operations(conn)

        proc = restart(proc, work_dir, log_path, scriptRunHistoryKinds="ENTRYPOINT")
        test_entrypoints_survive_restart()
        with admin_conn() as conn:
            test_history_rows_when_kind_enabled(conn)

        proc = restart(proc, work_dir, log_path, tlsEnabled=True, tlsKeystorePath="certs/mpp.p12",
                       tlsKeystorePassword="change_it")
        test_https()
        with tls_admin_conn() as conn:
            check_status("the operations port speaks TLS too", conn.list_entrypoints(), "OK")

        bu.stop_server(proc)
        proc = None
        section("An httpPort equal to port refuses to start")
        write_config(work_dir, httpPort=PORT)
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
