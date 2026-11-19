import os

import base_utils as bu
from base_utils import Conn

from . import ops as op_kinds
from .values import to_json_text

MAX_MEMORY = "1mb"
MAX_PAGE_SIZE = "8kb"
MAX_ENTRY_SIZE = "4kb"

ADMIN_USERNAME = "admin"
ADMIN_PASSWORD = bu.PASSWORD

ACCEPTED = ("OK", "CREATED", "UPDATED", "DELETED")


def accepted(response: dict) -> bool:
    return response.get("status") in ACCEPTED


class Outcome:
    def __init__(self):
        self.collections = set()
        self.indexed = {}

    def note_index(self, project: str, coll: str, field: str) -> None:
        self.indexed.setdefault((project, coll), set()).add(field)

    def forget_index(self, project: str, coll: str, field: str) -> None:
        self.indexed.get((project, coll), set()).discard(field)

    def forget_collection(self, project: str, coll: str) -> None:
        self.collections.discard((project, coll))
        self.indexed.pop((project, coll), None)

    def forget_project(self, project: str) -> None:
        for key in [c for c in self.collections if c[0] == project]:
            self.forget_collection(*key)

    def live(self) -> list:
        return sorted(self.collections)

    def indexed_fields(self, project: str, coll: str) -> list:
        return sorted(self.indexed.get((project, coll), set()))


class Executor:
    def __init__(self, work_dir: str, port: int):
        self.work_dir = work_dir
        self.port = port
        self.log_path = os.path.join(work_dir, "server.log")
        self.proc = None
        self._conn = None

    def start(self) -> None:
        bu.write_config(self.work_dir, self.port, maxMemory=MAX_MEMORY, maxPageSize=MAX_PAGE_SIZE,
                        maxEntrySize=MAX_ENTRY_SIZE, syncWrites=True)
        self.proc = bu.start_server(self.work_dir, self.log_path, port=self.port,
                                    jar=os.environ.get("FUZZ_JAR"))

    def stop(self) -> None:
        self._drop_conn()
        bu.stop_server(self.proc, port=self.port)
        self.proc = None

    def restart(self) -> None:
        self.stop()
        self.start()

    def _drop_conn(self) -> None:
        if self._conn is not None:
            self._conn.close()
            self._conn = None

    def conn(self) -> Conn:
        if self._conn is None:
            self._conn = Conn(port=self.port)
            self._conn.authenticate(ADMIN_USERNAME, ADMIN_PASSWORD)
        return self._conn

    def request(self, payload: dict) -> dict:
        return self.conn().send_raw(to_json_text(payload))

    def apply(self, plan: list, outcome: Outcome = None) -> Outcome:
        outcome = outcome or Outcome()
        for op in plan:
            self._apply_one(op, outcome)
        return outcome

    def wipe(self) -> None:
        response = self.request({"type": "LIST_PROJECTS"})
        for name in (response.get("projects") or []):
            self.request({"type": "DROP_PROJECT", "projectName": name})

    def settle_indexes(self, outcome: Outcome, fields: list) -> Outcome:
        for project, coll in outcome.live():
            for field in fields:
                if field in outcome.indexed_fields(project, coll):
                    continue
                op = op_kinds.Op(kind=op_kinds.CREATE_INDEX, project=project, coll=coll, field=field)
                self._record(op, self.request(self._payload(op)), outcome)
        return outcome

    def _apply_one(self, op, outcome: Outcome) -> None:
        if op.kind == op_kinds.RESTART:
            self.restart()
            return
        if op.kind == op_kinds.TRANSACTION:
            self._apply_transaction(op)
            return
        response = self.request(self._payload(op))
        self._record(op, response, outcome)

    def _apply_transaction(self, op) -> None:
        conn = self.conn()
        if not accepted(conn.send({"type": "START_TRANSACTION"})):
            return
        for nested in op.nested:
            self.request(self._payload(nested))
        conn.send({"type": "COMMIT_TRANSACTION"})

    @staticmethod
    def _record(op, response: dict, outcome: Outcome) -> None:
        if not accepted(response):
            return
        if op.kind == op_kinds.CREATE_COLLECTION:
            outcome.collections.add((op.project, op.coll))
        elif op.kind == op_kinds.CREATE_INDEX:
            outcome.note_index(op.project, op.coll, op.field)
        elif op.kind == op_kinds.DROP_INDEX:
            outcome.forget_index(op.project, op.coll, op.field)
        elif op.kind == op_kinds.DROP_COLLECTION:
            outcome.forget_collection(op.project, op.coll)
        elif op.kind == op_kinds.DROP_PROJECT:
            outcome.forget_project(op.project)

    @staticmethod
    def _payload(op) -> dict:
        base = {"type": op.kind}
        if op.project is not None:
            base["projectName"] = op.project
        if op.coll is not None:
            base["collectionName"] = op.coll
        if op.kind == op_kinds.SAVE:
            base["object"] = op.docs[0]
        elif op.kind == op_kinds.BULK_SAVE:
            base["objects"] = op.docs
        elif op.kind == op_kinds.DELETE:
            base["_id"] = op.ids[0]
        elif op.kind in (op_kinds.CREATE_INDEX, op_kinds.DROP_INDEX):
            base["fieldName"] = op.field
        return base
