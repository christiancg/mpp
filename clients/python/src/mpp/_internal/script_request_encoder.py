from __future__ import annotations

from collections.abc import Mapping, Sequence

from mpp._internal.request_encoder import RequestEncoder, request
from mpp._internal.values_codec import Json, JsonObject
from mpp.admin_types import Permissions, TransactionDecision, TriggerRunDecision, TriggerRunStatus
from mpp.query.types import PipelineStep
from mpp.scripting_types import (
    EntrypointDefinition,
    EntrypointMethod,
    MiddlewareDefinition,
    MiddlewareMatch,
    MiddlewareRule,
    ProcedureDefinition,
    ScheduleDefinition,
    TriggerDefinition,
    TriggerEvent,
)


def _optional(**fields: Json) -> JsonObject:
    return {name: value for name, value in fields.items() if value is not None}


def _grants(granted: Mapping[str, str]) -> JsonObject:
    return {key: str(value) for key, value in granted.items()}


def _permissions(granted: Permissions) -> JsonObject:
    global_permissions: list[Json] = [str(permission) for permission in granted.global_permissions]
    return {
        "admin": granted.admin,
        "globalPermissions": global_permissions,
        "projectPermissions": _grants(granted.projects),
        "collectionPermissions": _grants(granted.collections),
        "scriptPermissions": _grants(granted.scripts),
    }


def _text(value: str | None) -> str | None:
    return None if value is None else str(value)


def _inline_procedure(procedure: ProcedureDefinition) -> JsonObject:
    if procedure.if_version is not None:
        raise ValueError("an inline procedure takes no if_version")
    return {
        "name": procedure.name,
        "script": procedure.script,
        **_optional(description=procedure.description, enabled=procedure.enabled),
    }


def _target(kind: str, procedure_name: str | None, procedure: ProcedureDefinition | None) -> JsonObject:
    if (procedure_name is None) == (procedure is None):
        raise ValueError(f"{kind} takes exactly one of procedure_name and procedure")
    if procedure is not None:
        return {"procedure": _inline_procedure(procedure)}
    return {"procedureName": str(procedure_name)}


def _names(values: Sequence[str] | None) -> list[Json] | None:
    return None if values is None else [str(value) for value in values]


def _criteria(
    methods: Sequence[EntrypointMethod] | None, path_prefix: str | None, entrypoints: Sequence[str] | None
) -> JsonObject:
    return _optional(methods=_names(methods), pathPrefix=path_prefix, entrypoints=_names(entrypoints))


def _rule(rule: MiddlewareRule) -> Json:
    return _criteria(rule.methods, rule.path_prefix, rule.entrypoints)


def _match(match: MiddlewareMatch | None) -> JsonObject:
    if match is None:
        return {}
    exclude = None if match.exclude is None else [_rule(rule) for rule in match.exclude]
    return {"match": {**_criteria(match.methods, match.path_prefix, match.entrypoints), **_optional(exclude=exclude)}}


class ScriptRequestEncoder:
    __slots__ = ("_encoder",)

    def __init__(self, encoder: RequestEncoder) -> None:
        self._encoder = encoder

    def run_script(self, project: str, script: str, args: object) -> JsonObject:
        return {**request("RUN_SCRIPT", project), "script": script, **self._args(args)}

    def save_procedure(self, project: str, procedure: ProcedureDefinition) -> JsonObject:
        return {
            **request("SAVE_PROCEDURE", project),
            "name": procedure.name,
            "script": procedure.script,
            **_optional(description=procedure.description, enabled=procedure.enabled, ifVersion=procedure.if_version),
        }

    def delete_procedure(self, project: str, name: str) -> JsonObject:
        return {**request("DELETE_PROCEDURE", project), "name": name}

    def list_procedures(self, project: str, include_source: bool | None) -> JsonObject:
        return {**request("LIST_PROCEDURES", project), **_optional(includeSource=include_source)}

    def call_procedure(self, project: str, name: str, args: object) -> JsonObject:
        return {**request("CALL_PROCEDURE", project), "procedureName": name, **self._args(args)}

    def save_trigger(self, project: str, collection: str, trigger: TriggerDefinition) -> JsonObject:
        if not trigger.events:
            raise ValueError("a trigger needs at least one event")
        events: list[Json] = [str(event) for event in trigger.events]
        return {
            **request("SAVE_TRIGGER", project, collection),
            "name": trigger.name,
            "events": events,
            "procedureName": trigger.procedure_name,
            **_optional(
                timing=_text(trigger.timing),
                mode=_text(trigger.mode),
                allowCascade=trigger.allow_cascade,
                enabled=trigger.enabled,
                ifVersion=trigger.if_version,
            ),
        }

    def delete_trigger(self, project: str, collection: str, name: str) -> JsonObject:
        return {**request("DELETE_TRIGGER", project, collection), "name": name}

    def list_triggers(self, project: str, collection: str | None) -> JsonObject:
        return request("LIST_TRIGGERS", project, collection)

    def test_trigger(
        self,
        project: str,
        collection: str,
        name: str,
        event: TriggerEvent,
        document: object,
    ) -> JsonObject:
        return {
            **request("TEST_TRIGGER", project, collection),
            "name": name,
            "event": str(event),
            "document": self._encoder.document(document),
        }

    def save_schedule(self, project: str, schedule: ScheduleDefinition) -> JsonObject:
        if (schedule.cron is None) == (schedule.interval_ms is None):
            raise ValueError("a schedule takes exactly one of cron and interval_ms")
        return {
            **request("SAVE_SCHEDULE", project),
            "name": schedule.name,
            "procedureName": schedule.procedure_name,
            **_optional(cron=schedule.cron, intervalMs=schedule.interval_ms),
            **self._args(schedule.args),
            **_optional(
                timeoutMs=schedule.timeout_ms,
                description=schedule.description,
                enabled=schedule.enabled,
                ifVersion=schedule.if_version,
            ),
        }

    def delete_schedule(self, project: str, name: str) -> JsonObject:
        return {**request("DELETE_SCHEDULE", project), "name": name}

    def list_schedules(self, project: str, include_args: bool | None) -> JsonObject:
        return {**request("LIST_SCHEDULES", project), **_optional(includeArgs=include_args)}

    def save_entrypoint(self, project: str, entrypoint: EntrypointDefinition) -> JsonObject:
        target = _target("an entrypoint", entrypoint.procedure_name, entrypoint.procedure)
        return {
            **request("SAVE_ENTRYPOINT", project),
            "name": entrypoint.name,
            "method": str(entrypoint.method),
            "path": entrypoint.path,
            **target,
            **_optional(
                timeoutMs=entrypoint.timeout_ms,
                description=entrypoint.description,
                enabled=entrypoint.enabled,
                ifVersion=entrypoint.if_version,
            ),
        }

    def delete_entrypoint(self, project: str, name: str) -> JsonObject:
        return {**request("DELETE_ENTRYPOINT", project), "name": name}

    def list_entrypoints(self, project: str) -> JsonObject:
        return request("LIST_ENTRYPOINTS", project)

    def save_middleware(self, project: str, middleware: MiddlewareDefinition) -> JsonObject:
        target = _target("a middleware", middleware.procedure_name, middleware.procedure)
        return {
            **request("SAVE_MIDDLEWARE", project),
            "name": middleware.name,
            "phase": str(middleware.phase),
            **_optional(order=middleware.order),
            **_match(middleware.match),
            **target,
            **_optional(
                timeoutMs=middleware.timeout_ms,
                description=middleware.description,
                enabled=middleware.enabled,
                ifVersion=middleware.if_version,
            ),
        }

    def delete_middleware(self, project: str, name: str) -> JsonObject:
        return {**request("DELETE_MIDDLEWARE", project), "name": name}

    def list_middlewares(self, project: str) -> JsonObject:
        return request("LIST_MIDDLEWARES", project)

    def list_scripts(self) -> JsonObject:
        return request("LIST_SCRIPTS")

    def cancel_script(self, run_id: str) -> JsonObject:
        return {**request("CANCEL_SCRIPT"), "runId": run_id}

    def list_trigger_runs(self, status: TriggerRunStatus | None) -> JsonObject:
        return {**request("LIST_TRIGGER_RUNS"), **_optional(status=_text(status))}

    def resolve_trigger_run(self, run_id: str, decision: TriggerRunDecision) -> JsonObject:
        return {**request("RESOLVE_TRIGGER_RUN"), "runId": run_id, "decision": str(decision)}

    def list_transactions(self) -> JsonObject:
        return request("LIST_TRANSACTIONS")

    def resolve_transaction(self, dtx_id: str, decision: TransactionDecision) -> JsonObject:
        return {**request("RESOLVE_TRANSACTION"), "dtxId": dtx_id, "decision": str(decision)}

    def create_user(self, username: str, password: str, granted: Permissions | None) -> JsonObject:
        frame = {**request("CREATE_USER"), "username": username, "password": password}
        return frame if granted is None else {**frame, **_permissions(granted)}

    def delete_user(self, username: str) -> JsonObject:
        return {**request("DELETE_USER"), "username": username}

    def change_permissions(self, username: str, granted: Permissions) -> JsonObject:
        return {**request("CHANGE_PERMISSIONS"), "username": username, **_permissions(granted)}

    def set_password(self, username: str, current_password: str | None, new_password: str) -> JsonObject:
        return {
            **request("SET_PASSWORD"),
            "username": username,
            **_optional(currentPassword=current_password),
            "newPassword": new_password,
        }

    def list_users(self, steps: Sequence[PipelineStep]) -> JsonObject:
        if not steps:
            return request("LIST_USERS")
        return {**request("LIST_USERS"), "aggregationSteps": self._encoder.pipeline.steps(steps)}

    def _args(self, args: object) -> JsonObject:
        return {} if args is None else {"args": self._encoder.document(args)}
