import os
import subprocess
import sys
import tempfile

import base_utils as bu
import http_utils as hu
from base_utils import check, check_code, check_status, section

HOST = "127.0.0.1"
PORT = int(os.environ.get("MIDDLEWARE_TEST_PORT", "9461"))
HTTP_PORT = int(os.environ.get("MIDDLEWARE_HTTP_PORT", "9462"))
ADMIN_USERNAME = "admin"
PROJECT = "mw_test_project"
COLL = "orders"
SESSIONS = "sessions"
ACCESS_LOG = "access_log"
COUNTERS = "counters"
USER_PASSWORD = "password123"
LIMITED = "mw_limited"
READER = "mw_reader"
RUNNER = "mw_runner"
BASE = f"/{PROJECT}"
CLIENT_TIMEOUT_S = 120
IMPORTS = 'import args from "args";\nimport db from "db";\nimport { next, reject } from "middleware";\n'
ECHO = 'import args from "args";\nreturn args;'
AUTH = (IMPORTS + 'const token = (args.headers.authorization ?? "").replace(/^Bearer /, "");\n'
        f'const session = token === "" ? null : db.findById(db.name, "{SESSIONS}", token);\n'
        'if (session === null) {\n'
        '  reject(401, {error: "unauthorized"}, {"www-authenticate": "Bearer"});\n'
        '} else {\n'
        '  next({locals: {user: session.user}});\n'
        '}')

bu.configure(host=HOST, port=PORT, username=ADMIN_USERNAME, password=bu.PASSWORD)
HTTP = hu.HttpTarget(HOST, HTTP_PORT)
request = HTTP.request


class Conn(bu.Conn):
    def expose(self, name, method, path, script) -> dict:
        return self.send({"type": "SAVE_ENTRYPOINT", "projectName": PROJECT, "name": name, "method": method,
                          "path": path, "procedure": {"name": name, "script": script}})

    def save_middleware(self, name, phase, script=None, order=0, match=None, **extra) -> dict:
        payload = {"type": "SAVE_MIDDLEWARE", "projectName": PROJECT, "name": name, "phase": phase, "order": order}
        if script is not None:
            payload["procedure"] = {"name": f"{name}Mw", "script": script}
        if match is not None:
            payload["match"] = match
        payload.update(extra)
        return self.send(payload)

    def delete_middleware(self, name) -> dict:
        return self.send({"type": "DELETE_MIDDLEWARE", "projectName": PROJECT, "name": name})

    def list_middlewares(self) -> dict:
        return self.send({"type": "LIST_MIDDLEWARES", "projectName": PROJECT})

    def middleware(self, name) -> dict:
        listed = self.list_middlewares().get("middlewares") or []
        return next((entry for entry in listed if entry.get("name") == name), {})

    def save_doc(self, collection, document) -> dict:
        return self.send({"type": "SAVE", "projectName": PROJECT, "collectionName": collection, "object": document})

    def find(self, collection, doc_id) -> dict:
        return self.send({"type": "FIND_BY_ID", "projectName": PROJECT, "collectionName": collection,
                          "_id": doc_id}).get("object") or {}

    def scan(self, collection) -> list:
        return self.send({"type": "AGGREGATE", "projectName": PROJECT, "collectionName": collection,
                          "aggregationSteps": [{"type": "SKIP", "skip": 0}]}).get("results") or []


def admin_conn() -> Conn:
    return Conn.authenticated()


def user_conn(username: str) -> Conn:
    return Conn.authenticated(username, USER_PASSWORD)


def under(prefix: str) -> dict:
    return {"pathPrefix": prefix}


def cleanup(conn: Conn, *names):
    for name in names:
        conn.delete_middleware(name)


def write_config(work_dir: str, **overrides):
    settings = {
        "httpPort": HTTP_PORT, "scriptsEnabled": True, "scriptInstructionBudget": 2_000_000_000,
        "scriptTimeoutMs": 20000, "maxConcurrentScriptsPerUser": 0, "maxConcurrentScriptsPerProject": 0,
        "scriptRunHistoryEnabled": True, "scriptRunHistoryKinds": "CALL_PROCEDURE",
    }
    settings.update(overrides)
    bu.write_config(work_dir, PORT, **settings)


def setup_data(conn: Conn):
    section("Setup")
    check_status("create the project", conn.send({"type": "CREATE_PROJECT", "projectName": PROJECT}), "OK")
    for collection in (COLL, SESSIONS, ACCESS_LOG, COUNTERS):
        check_status(f"create {collection}", conn.send({"type": "CREATE_COLLECTION", "projectName": PROJECT,
                                                        "collectionName": collection}), "OK")
    check_status("store a session", conn.save_doc(SESSIONS, {"_id": "tok1", "user": "alice"}), "OK")
    check_status("store an order", conn.save_doc(COLL, {"_id": "o1", "qty": 2}), "OK")


def test_ingress_rewrites_request(conn: Conn):
    section("An ingress middleware rewrites the path parameters, query, headers and body the target reads")
    check_status("expose an echo", conn.expose("rewriteEcho", "POST", "/rw/{id}", ECHO), "OK")
    check_status("save the rewriting middleware", conn.save_middleware("rewriter", "INGRESS", IMPORTS + (
        'next({pathParams: {id: args.pathParams.id.toUpperCase()}, query: {added: "yes"},'
        ' headers: {authorization: null, "x-seen": "1"}, body: {replaced: args.body.original}});'),
        match=under("/rw")), "OK")
    reply = request("POST", f"{BASE}/rw/o1?keep=k", body={"original": 7}, headers={"Authorization": "Bearer x"})
    args = reply.json() if reply.status == 200 else {}
    check("it answers 200", reply.status == 200, f"got {reply.status} {reply.body!r}")
    check("the path parameter is rewritten", args.get("pathParams") == {"id": "O1"}, f"got {args}")
    check("the query gains a key and keeps the others", args.get("query") == {"keep": "k", "added": "yes"},
          f"got {args.get('query')}")
    headers = args.get("headers", {})
    check("the authorization header is stripped", "authorization" not in headers, f"got {headers}")
    check("a header is added", headers.get("x-seen") == "1", f"got {headers}")
    check("the body is replaced", args.get("body") == {"replaced": 7}, f"got {args.get('body')}")
    cleanup(conn, "rewriter")


def test_egress_rewrites_response(conn: Conn):
    section("Egress middlewares rewrite the status, headers and body, in order")
    check_status("expose a JSON answer", conn.expose("egressTarget", "GET", "/eg", "return {a: 1};"), "OK")
    check_status("the first egress changes the status and adds a header", conn.save_middleware(
        "egressOne", "EGRESS", IMPORTS + 'next({status: 202, headers: {"x-one": "1"}});', order=1,
        match=under("/eg")), "OK")
    check_status("the second replaces the body with a string", conn.save_middleware(
        "egressTwo", "EGRESS", IMPORTS + 'next({body: "was " + args.response.status});', order=2,
        match=under("/eg")), "OK")
    reply = request("GET", f"{BASE}/eg")
    check("the client gets 202", reply.status == 202, f"got {reply.status}")
    check("with the added header", reply.headers.get("x-one") == "1", f"got {reply.headers}")
    check("and a text body built from the earlier change", reply.text() == "was 202"
          and reply.headers.get("content-type", "").startswith("text/plain"), f"got {reply.headers} {reply.body!r}")
    cleanup(conn, "egressOne", "egressTwo")


def test_bad_change_is_500(conn: Conn, log_path: str):
    section("A typo in next() fails the run loudly")
    check_status("expose a target", conn.expose("typoTarget", "GET", "/typo", "return 1;"), "OK")
    check_status("save a middleware with a typo", conn.save_middleware(
        "typo", "INGRESS", IMPORTS + 'next({header: {a: "1"}});', match=under("/typo")), "OK")
    reply = request("GET", f"{BASE}/typo")
    check("it answers 500", reply.status == 500, f"got {reply.status}")
    run_id = reply.json().get("runId", "?") if reply.status == 500 else "?"
    check("the log names the offending key", any(
        run_id in line and "'header'" in line for line in bu.read_log(log_path).splitlines()), "no log line")
    cleanup(conn, "typo")


def test_auth_filter_rejects_and_passes(conn: Conn):
    section("An auth filter rejects without a token and hands the user to the target with one")
    check_status("expose a whoami", conn.expose("whoami", "GET", "/auth/me",
                                                'import args from "args";\nreturn {user: args.locals.user};'), "OK")
    check_status("save the auth filter", conn.save_middleware("authFilter", "INGRESS", AUTH, order=10,
                                                              match=under("/auth")), "OK")
    reply = request("GET", f"{BASE}/auth/me")
    check("no token is 401", reply.status == 401, f"got {reply.status}")
    check("with WWW-Authenticate", reply.headers.get("www-authenticate") == "Bearer", f"got {reply.headers}")
    reply = request("GET", f"{BASE}/auth/me", headers={"Authorization": "Bearer nope"})
    check("an unknown token is 401", reply.status == 401, f"got {reply.status}")
    reply = request("GET", f"{BASE}/auth/me", headers={"Authorization": "Bearer tok1"})
    check("a valid token reaches the target with the user", reply.status == 200
          and reply.json() == {"user": "alice"}, f"got {reply.status} {reply.body!r}")


def test_cache_hit_short_circuits(conn: Conn):
    section("A cache hit answers 200 from the middleware and the target never runs")
    script = (f'import db from "db";\nconst c = db.findById(db.name, "{COUNTERS}", "target") ?? {{_id: "target", n: 0}};'
              f'\nc.n = c.n + 1;\ndb.save(db.name, "{COUNTERS}", c);\nreturn "fresh";')
    check_status("expose a counting target", conn.expose("counted", "GET", "/cache/item", script), "OK")
    check_status("save a cache that always hits", conn.save_middleware(
        "cache", "INGRESS", IMPORTS + 'reject(200, "cached");', match=under("/cache")), "OK")
    reply = request("GET", f"{BASE}/cache/item")
    check("the cached body is served", reply.status == 200 and reply.text() == "cached", f"got {reply.body!r}")
    check("the target never ran", conn.find(COUNTERS, "target") == {}, f"got {conn.find(COUNTERS, 'target')}")
    cleanup(conn, "cache")
    check("without the cache the target runs", request("GET", f"{BASE}/cache/item").text() == "fresh", "")


def test_egress_sees_ingress_rejects_and_target_failures(conn: Conn):
    section("An egress access log records ingress rejections and target failures too")
    check_status("expose a failing target", conn.expose("failing", "GET", "/log/fail", "throw new Error('boom');"),
                 "OK")
    check_status("expose a guarded target", conn.expose("guarded", "GET", "/log/guarded", "return 1;"), "OK")
    check_status("guard it", conn.save_middleware("logGuard", "INGRESS", IMPORTS + "reject(401);",
                                                  match={"entrypoints": ["guarded"]}), "OK")
    check_status("log every response", conn.save_middleware("accessLog", "EGRESS", IMPORTS + (
        f'db.save(db.name, "{ACCESS_LOG}", {{path: args.path, status: args.response.status}});\nnext();'),
        match=under("/log")), "OK")
    check("the guarded route is 401", request("GET", f"{BASE}/log/guarded").status == 401, "")
    check("the failing route is 500", request("GET", f"{BASE}/log/fail").status == 500, "")
    statuses = sorted(row.get("status") for row in conn.scan(ACCESS_LOG))
    check("the access log holds both", statuses == [401, 500], f"got {statuses}")
    cleanup(conn, "logGuard", "accessLog")


def test_order_and_ties(conn: Conn):
    section("Middlewares run by order, then by name")
    check_status("expose a trace target", conn.expose("traced", "GET", "/order/trace",
                                                      'import args from "args";\nreturn args.locals.trace;'), "OK")
    append = IMPORTS + 'next({locals: {trace: (args.locals.trace ?? "") + args.middleware.name + ";"}});'
    for name, order in (("zeta", 5), ("alpha", 5), ("first", -1)):
        check_status(f"save {name}", conn.save_middleware(name, "INGRESS", append, order=order,
                                                          match=under("/order")), "OK")
    reply = request("GET", f"{BASE}/order/trace")
    check("the trace is in execution order", reply.text() == "first;alpha;zeta;", f"got {reply.body!r}")
    listed = [entry.get("name") for entry in conn.list_middlewares().get("middlewares") or []]
    check("LIST_MIDDLEWARES shows the same order", listed.index("first") < listed.index("alpha")
          < listed.index("zeta"), f"got {listed}")
    cleanup(conn, "zeta", "alpha", "first")


def test_match_methods_prefix_and_entrypoints(conn: Conn):
    section("match narrows a middleware by method, path prefix and entrypoint name")
    check_status("expose a read", conn.expose("matchRead", "GET", "/match/items/{id}", "return 'read';"), "OK")
    check_status("expose a write", conn.expose("matchWrite", "POST", "/match/items", "return 'write';"), "OK")
    check_status("expose an archive", conn.expose("matchArchive", "GET", "/match-archive", "return 'archive';"),
                 "OK")
    check_status("block writes under /match", conn.save_middleware(
        "blockWrites", "INGRESS", IMPORTS + "reject(403);", match={"methods": ["post"], "pathPrefix": "/match"}),
        "OK")
    check("the write is blocked", request("POST", f"{BASE}/match/items").status == 403, "")
    check("the read is not", request("GET", f"{BASE}/match/items/i1").text() == "read", "")
    check("a sibling prefix is not covered", request("GET", f"{BASE}/match-archive").text() == "archive", "")
    check_status("narrow it to one entrypoint by name", conn.save_middleware(
        "blockWrites", "INGRESS", procedureName="blockWritesMw", match={"entrypoints": ["matchRead"]}), "OK")
    check("now the read is blocked", request("GET", f"{BASE}/match/items/i1").status == 403, "")
    check("and the write is served", request("POST", f"{BASE}/match/items").text() == "write", "")
    cleanup(conn, "blockWrites")


def test_exclude_makes_one_entrypoint_public(conn: Conn):
    section("match.exclude leaves one entrypoint of a guarded path public")
    check_status("expose a public listing", conn.expose("listOrders", "GET", "/shop/orders", "return [];"), "OK")
    check_status("expose a private read", conn.expose("getOrder", "GET", "/shop/orders/{id}", "return {};"), "OK")
    check_status("guard /shop but exclude listOrders", conn.save_middleware(
        "shopAuth", "INGRESS", AUTH, match={"pathPrefix": "/shop", "exclude": [{"entrypoints": ["listOrders"]}]}),
        "OK")
    check("the private read needs a token", request("GET", f"{BASE}/shop/orders/o1").status == 401, "")
    check("the listing is public", request("GET", f"{BASE}/shop/orders").status == 200, "")
    check_code("an empty exclude rule is refused", conn.save_middleware(
        "shopAuth", "INGRESS", procedureName="shopAuthMw", match={"pathPrefix": "/shop", "exclude": [{}]}),
        "ERROR", "400-49")
    check_status("save it again without the rule", conn.save_middleware(
        "shopAuth", "INGRESS", procedureName="shopAuthMw", match=under("/shop")), "OK")
    check("the listing needs a token now", request("GET", f"{BASE}/shop/orders").status == 401, "")
    cleanup(conn, "shopAuth")


def test_undecided_and_failed_middlewares(conn: Conn):
    section("A middleware that decides nothing, or throws, is 500 and leaks nothing")
    check_status("expose a target", conn.expose("strictTarget", "GET", "/strict/x", "return 'reached';"), "OK")
    check_status("save a silent middleware", conn.save_middleware("silent", "INGRESS", IMPORTS + "const x = 1;",
                                                                  match=under("/strict")), "OK")
    reply = request("GET", f"{BASE}/strict/x")
    check("deciding nothing is 500", reply.status == 500 and "reached" not in reply.text(), f"got {reply.body!r}")
    check_status("make it throw", conn.save_middleware("silent", "INGRESS",
                                                       IMPORTS + "throw new Error('very secret');",
                                                       match=under("/strict")), "OK")
    reply = request("GET", f"{BASE}/strict/x")
    check("a throw is 500", reply.status == 500, f"got {reply.status}")
    check("the body carries only a run id", set(reply.json()) == {"error", "runId"}
          and "secret" not in reply.text(), f"got {reply.body!r}")
    cleanup(conn, "silent")


def test_user_disabled_is_skipped_revoked_fails_closed(admin: Conn):
    section("A middleware the user disables is skipped; one whose definer is revoked fails closed")
    check_status("expose a target", admin.expose("closedTarget", "GET", "/closed/x", "return 'open';"), "OK")
    check_status("save a disabled blocker", admin.save_middleware(
        "disabledBlocker", "INGRESS", IMPORTS + "reject(403);", match=under("/closed"), enabled=False), "OK")
    check("a disabled middleware is skipped", request("GET", f"{BASE}/closed/x").text() == "open", "")
    check_status("create a limited definer", admin.send({
        "type": "CREATE_USER", "username": LIMITED, "password": USER_PASSWORD, "admin": False,
        "projectPermissions": {PROJECT: "READ"}, "scriptPermissions": {PROJECT: "MANAGE"}}), "OK")
    with user_conn(LIMITED) as definer:
        check_status("the definer saves a pass-through guard", definer.save_middleware(
            "limitedGuard", "INGRESS", IMPORTS + "next();", match=under("/closed")), "OK")
    check("the guard lets requests through", request("GET", f"{BASE}/closed/x").text() == "open", "")
    check_status("revoke the definer's script grant", admin.send({
        "type": "CHANGE_PERMISSIONS", "username": LIMITED, "admin": False, "globalPermissions": [],
        "projectPermissions": {PROJECT: "READ"}, "collectionPermissions": {}, "scriptPermissions": {}}), "OK")
    stored = admin.middleware("limitedGuard")
    check("revoking disables it with a reason", stored.get("enabled") is False
          and "script RUN" in str(stored.get("disabledReason")), f"got {stored}")
    reply = request("GET", f"{BASE}/closed/x")
    check("the guarded route fails closed with 500, never 200", reply.status == 500, f"got {reply.status}")
    cleanup(admin, "disabledBlocker", "limitedGuard")
    check("deleting it opens the route again", request("GET", f"{BASE}/closed/x").text() == "open", "")


def test_inline_procedure_and_delete_guard(conn: Conn):
    section("An inline middleware procedure is an ordinary procedure, protected while referenced")
    check_status("save an inline middleware", conn.save_middleware(
        "guardKeeper", "EGRESS", IMPORTS + "next();", match=under("/keeper")), "OK")
    check_code("deleting its procedure is refused", conn.send({
        "type": "DELETE_PROCEDURE", "projectName": PROJECT, "name": "guardKeeperMw"}), "ERROR", "400-49")
    check_status("delete the middleware", conn.delete_middleware("guardKeeper"), "OK")
    check_status("deleting a missing middleware is OK", conn.delete_middleware("guardKeeper"), "OK")
    check_status("now the procedure can go", conn.send({
        "type": "DELETE_PROCEDURE", "projectName": PROJECT, "name": "guardKeeperMw"}), "OK")
    check_code("an unknown phase is 400-49", conn.save_middleware("bad", "BEFORE", IMPORTS + "next();"),
               "ERROR", "400-49")
    check_code("an unknown procedure is 404-8", conn.save_middleware("bad", "INGRESS", procedureName="missing"),
               "NOT_FOUND", "404-8")


def test_auth_of_the_operations(admin: Conn):
    section("Saving needs script MANAGE, listing needs READ")
    check_status("create a reader", admin.send({
        "type": "CREATE_USER", "username": READER, "password": USER_PASSWORD, "admin": False,
        "projectPermissions": {PROJECT: "READ"}}), "OK")
    check_status("create a runner", admin.send({
        "type": "CREATE_USER", "username": RUNNER, "password": USER_PASSWORD, "admin": False,
        "projectPermissions": {PROJECT: "READ_WRITE"}, "scriptPermissions": {PROJECT: "RUN"}}), "OK")
    with user_conn(RUNNER) as runner:
        check_code("a RUN-only user cannot save", runner.save_middleware("nope", "INGRESS", IMPORTS + "next();"),
                   "FORBIDDEN", "403-1")
        check_code("nor delete", runner.delete_middleware("authFilter"), "FORBIDDEN", "403-1")
    with user_conn(READER) as reader:
        check_status("a READ user can list", reader.list_middlewares(), "OK")


def test_middlewares_survive_restart():
    section("Middlewares apply again after a restart")
    check("the auth filter still rejects", request("GET", f"{BASE}/auth/me").status == 401, "")
    check("and still passes a valid token", request("GET", f"{BASE}/auth/me",
                                                    headers={"Authorization": "Bearer tok1"}).status == 200, "")


def test_history_rows_when_kind_enabled(conn: Conn):
    section("Middleware runs are recorded when MIDDLEWARE is a history kind")
    request("GET", f"{BASE}/auth/me", headers={"Authorization": "Bearer tok1"})

    def rows():
        response = conn.send({"type": "AGGREGATE", "projectName": PROJECT, "collectionName": "script_runs",
                              "aggregationSteps": [{"type": "SORT", "fieldName": "startedAt", "ascending": False}]})
        found = response.get("results") or []
        kinds = {row.get("kind") for row in found}
        return found if {"MIDDLEWARE", "ENTRYPOINT"} <= kinds else None

    found = bu.wait_until(rows, 20, 0.3) or []
    middleware_rows = [row for row in found if row.get("kind") == "MIDDLEWARE"]
    check("a MIDDLEWARE row is written beside the ENTRYPOINT one", bool(middleware_rows), "no MIDDLEWARE row")
    if middleware_rows:
        check("naming the middleware and its procedure", middleware_rows[0].get("name") == "authFilter"
              and middleware_rows[0].get("procedure") == "authFilterMw", f"got {middleware_rows[0]}")


def client(cwd: str, *args, stdin: bytes = b""):
    command = ["java", "-jar", bu.JAR_PATH, *args, "--port", str(PORT), "--password", bu.PASSWORD]
    return subprocess.run(command, input=stdin, capture_output=True, cwd=cwd, timeout=CLIENT_TIMEOUT_S)


def test_dump_restore_round_trip(proc, work_dir: str, log_path: str):
    section("--dump and --restore carry the middlewares into a fresh server")
    scratch = tempfile.mkdtemp(prefix="mpp-middleware-client-")
    dumped = client(scratch, "--dump", "--projects", PROJECT)
    check("--dump exits 0", dumped.returncode == 0, dumped.stderr.decode())
    check("the dump holds SAVE_MIDDLEWARE", b"SAVE_MIDDLEWARE" in dumped.stdout, "")
    bu.stop_server(proc)
    fresh = tempfile.mkdtemp(prefix="mpp-middleware-restore-")
    write_config(fresh)
    proc = bu.start_server(fresh, log_path)
    restored = client(scratch, "--restore", stdin=dumped.stdout)
    check("--restore exits 0", restored.returncode == 0, restored.stderr.decode())
    with admin_conn() as conn:
        check("the auth filter is listed", conn.middleware("authFilter").get("phase") == "INGRESS",
              f"got {conn.middleware('authFilter')}")
    check("and applies on the restored server", request("GET", f"{BASE}/auth/me").status == 401, "")
    check("with the restored session data", request("GET", f"{BASE}/auth/me",
                                                    headers={"Authorization": "Bearer tok1"}).status == 200, "")
    return proc


def restart(proc, work_dir: str, log_path: str, **overrides):
    bu.stop_server(proc)
    write_config(work_dir, **overrides)
    return bu.start_server(work_dir, log_path)


def main():
    bu.banner("HTTP middlewares test suite")
    bu.require_jar()
    if bu.port_open() or HTTP.port_open():
        print(f"port {PORT} or {HTTP_PORT} is already in use", file=sys.stderr)
        sys.exit(1)
    work_dir = tempfile.mkdtemp(prefix="mpp-middleware-test-")
    log_path = os.path.join(work_dir, "server.out")
    proc = None
    try:
        print(f"work dir: {work_dir}")
        write_config(work_dir)
        proc = bu.start_server(work_dir, log_path)
        with admin_conn() as conn:
            setup_data(conn)
            test_ingress_rewrites_request(conn)
            test_egress_rewrites_response(conn)
            test_bad_change_is_500(conn, log_path)
            test_auth_filter_rejects_and_passes(conn)
            test_cache_hit_short_circuits(conn)
            test_egress_sees_ingress_rejects_and_target_failures(conn)
            test_order_and_ties(conn)
            test_match_methods_prefix_and_entrypoints(conn)
            test_exclude_makes_one_entrypoint_public(conn)
            test_undecided_and_failed_middlewares(conn)
            test_user_disabled_is_skipped_revoked_fails_closed(conn)
            test_inline_procedure_and_delete_guard(conn)
            test_auth_of_the_operations(conn)

        proc = restart(proc, work_dir, log_path, scriptRunHistoryKinds="ENTRYPOINT,MIDDLEWARE")
        test_middlewares_survive_restart()
        with admin_conn() as conn:
            test_history_rows_when_kind_enabled(conn)

        proc = test_dump_restore_round_trip(proc, work_dir, log_path)
    except Exception as exc:  # noqa: BLE001
        print(f"\nunexpected failure: {exc}", file=sys.stderr)
        bu.dump_log(log_path)
        bu.record_failure()
    finally:
        bu.stop_server(proc)

    bu.summary(on_failure=lambda: bu.dump_log(log_path))


if __name__ == "__main__":
    main()
