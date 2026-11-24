from __future__ import annotations

import asyncio
import contextlib
from collections.abc import AsyncIterator

import pytest

from mpp import (
    AsyncMppClient,
    AsyncProject,
    ConflictError,
    EntrypointDefinition,
    EntrypointMethod,
    MiddlewareDefinition,
    MiddlewareMatch,
    MiddlewarePhase,
    MiddlewareRule,
    MppError,
    ProcedureDefinition,
    ScheduleDefinition,
    TriggerDefinition,
    TriggerEvent,
    TriggerRunDecision,
    TriggerRunStatus,
    TriggerTiming,
)
from tests.it.conftest import eventually

pytestmark = pytest.mark.it
SERVER_CONFIG = {"triggersEnabled": "true", "triggerMaxAttempts": "1", "triggerRetryBackoffMs": "100"}


@pytest.fixture
async def shop(client: AsyncMppClient) -> AsyncIterator[AsyncProject]:
    project = client.project("script_it")
    await project.create()
    for name in ("orders", "audit", "notes"):
        await project.collection(name).create()
    yield project
    await project.drop()


async def test_run_script_returns_its_value_and_logs(shop: AsyncProject) -> None:
    result = await shop.run_script(
        'import args from "args";\nconsole.log("hi " + args.name);\nreturn { greeting: "hello " + args.name };',
        {"name": "Ana"},
    )
    assert result.value == {"greeting": "hello Ana"}
    assert result.logs[0] == "hi Ana"
    assert (await shop.run_script("return 1 + 1;")).value == 2


async def test_procedures_are_saved_listed_called_and_deleted(shop: AsyncProject) -> None:
    procedures = shop.procedures()
    version = await procedures.save(
        ProcedureDefinition("double_it", 'import args from "args";\nreturn args.n * 2;', description="doubles")
    )
    assert version > 0
    assert any(procedure.name == "double_it" for procedure in await procedures.list())
    with_source = [
        procedure for procedure in await procedures.list(include_source=True) if procedure.name == "double_it"
    ]
    assert with_source[0].source
    assert (await procedures.call("double_it", {"n": 21})).value == 42
    with pytest.raises(MppError):
        await procedures.save(ProcedureDefinition("double_it", "return 0;", if_version=version + 10))
    await procedures.delete("double_it")
    with pytest.raises(MppError):
        await procedures.call("double_it")


async def test_triggers_fire_test_reject_and_list(shop: AsyncProject) -> None:
    await shop.procedures().save(
        ProcedureDefinition(
            "audit_write",
            'import db from "db";\nimport args from "args";\n'
            'db.save(db.name, "audit", { _id: args.id, event: args.event });\nreturn true;',
        )
    )
    await shop.procedures().save(
        ProcedureDefinition(
            "require_total",
            "export default function (document) {\n"
            '  if (document.total == null) { throw new Error("total missing"); }\n  return true;\n}',
        )
    )
    orders = shop.collection("orders")
    saved = await orders.triggers().save(TriggerDefinition("audit", "audit_write", (TriggerEvent.CREATED,)))
    assert saved.version > 0
    await orders.triggers().save(
        TriggerDefinition("guard", "require_total", (TriggerEvent.CREATED,), timing=TriggerTiming.BEFORE)
    )
    assert len(await orders.triggers().list()) == 2
    assert {"audit", "guard"} <= {trigger.name for trigger in await shop.list_triggers()}
    assert (await orders.triggers().test("guard", TriggerEvent.CREATED, {"_id": "t1"})).decision
    with pytest.raises(MppError, match="total missing") as refused:
        await orders.save({"_id": "o1"})
    assert refused.value.raw_code == "400-21"
    await orders.save({"_id": "o2", "total": 5})
    audit = shop.collection("audit")
    assert await eventually(lambda: audit.find_by_id("o2"), lambda row: row is not None) is not None
    await orders.triggers().delete("audit")
    await orders.triggers().delete("guard")
    assert await orders.triggers().list() == []


async def test_dead_trigger_runs_are_listed_and_resolved(client: AsyncMppClient, shop: AsyncProject) -> None:
    await shop.procedures().save(ProcedureDefinition("always_fails", 'throw new Error("boom");'))
    notes = shop.collection("notes")
    await notes.triggers().save(TriggerDefinition("failing", "always_fails", (TriggerEvent.CREATED,)))
    await notes.save({"_id": "n1"})
    runs = client.admin().trigger_runs()
    dead = await eventually(lambda: runs.list(TriggerRunStatus.DEAD), lambda listed: len(listed) > 0)
    assert dead
    assert len(await runs.list()) >= len(dead)
    assert await runs.resolve(dead[0].run_id, TriggerRunDecision.DISCARD)


async def test_schedules_are_saved_listed_and_deleted(shop: AsyncProject) -> None:
    await shop.procedures().save(ProcedureDefinition("noop", "return 0;"))
    schedules = shop.schedules()
    assert await schedules.save(ScheduleDefinition("hourly", "noop", cron="0 * * * *", args={"a": 1})) > 0
    await schedules.save(ScheduleDefinition("poll", "noop", interval_ms=300_000))
    assert len(await schedules.list()) == 2
    assert any("args" in schedule.raw for schedule in await schedules.list(include_args=True))
    await schedules.delete("hourly")
    await schedules.delete("poll")
    assert await schedules.list() == []


async def test_entrypoints_are_saved_listed_refused_and_deleted(shop: AsyncProject) -> None:
    await shop.procedures().save(ProcedureDefinition("noop", "return 0;"))
    entrypoints = shop.entrypoints()
    ping = await entrypoints.save(EntrypointDefinition("ping", EntrypointMethod.GET, "/ping", procedure_name="noop"))
    assert ping.version == 1
    echo = await entrypoints.save(
        EntrypointDefinition(
            "echo",
            EntrypointMethod.POST,
            "/echo",
            procedure=ProcedureDefinition(
                "echo_body", 'import args from "args";\nreturn {status: 200, body: args.body};'
            ),
        )
    )
    assert echo.procedure_version == 1
    assert "echo_body" in {procedure.name for procedure in await shop.procedures().list()}
    listed = {entrypoint.name: entrypoint for entrypoint in await entrypoints.list()}
    assert set(listed) == {"ping", "echo"}
    assert (listed["ping"].method, listed["ping"].url, listed["ping"].procedure_name) == (
        "GET",
        "/script_it/ping",
        "noop",
    )
    with pytest.raises(ConflictError) as refused:
        await entrypoints.save(EntrypointDefinition("ping2", EntrypointMethod.GET, "/ping", procedure_name="noop"))
    assert refused.value.raw_code == "409-19"
    await entrypoints.delete("ping")
    await entrypoints.delete("echo")
    assert await entrypoints.list() == []
    assert "echo_body" in {procedure.name for procedure in await shop.procedures().list()}


async def test_middlewares_are_saved_listed_and_deleted(shop: AsyncProject) -> None:
    await shop.procedures().save(ProcedureDefinition("check_token", 'import { next } from "middleware";\nnext({});'))
    middlewares = shop.middlewares()
    cors = await middlewares.save(
        MiddlewareDefinition(
            "cors",
            MiddlewarePhase.EGRESS,
            procedure=ProcedureDefinition(
                "cors_headers",
                'import { next } from "middleware";\nnext({headers: {"access-control-allow-origin": "*"}});',
            ),
        )
    )
    assert (cors.version, cors.procedure_version) == (1, 1)
    match = MiddlewareMatch(
        methods=(EntrypointMethod.POST,),
        path_prefix="/orders",
        exclude=(MiddlewareRule(entrypoints=("listOrders",)),),
    )
    auth = await middlewares.save(
        MiddlewareDefinition("auth", MiddlewarePhase.INGRESS, "check_token", order=10, match=match, timeout_ms=2000)
    )
    assert auth.version == 1
    listed = await middlewares.list()
    assert [(middleware.name, middleware.phase) for middleware in listed] == [("auth", "INGRESS"), ("cors", "EGRESS")]
    assert (listed[0].order, listed[0].procedure_name, listed[0].timeout_ms, listed[0].enabled) == (
        10,
        "check_token",
        2000,
        True,
    )
    assert listed[0].match == MiddlewareMatch(
        (EntrypointMethod.POST,), "/orders", None, (MiddlewareRule(entrypoints=("listOrders",)),)
    )
    assert (listed[1].match, listed[1].procedure_name, listed[1].definer) == (None, "cors_headers", "admin")
    await middlewares.delete("auth")
    await middlewares.delete("cors")
    assert await middlewares.list() == []
    assert "cors_headers" in {procedure.name for procedure in await shop.procedures().list()}


async def test_a_long_script_is_listed_and_cancelled(client: AsyncMppClient, shop: AsyncProject) -> None:
    running = asyncio.create_task(shop.run_script("let i = 0;\nwhile (true) { i++; }"))
    scripts = await eventually(lambda: client.admin().scripts().list(), lambda listed: len(listed) > 0)
    assert await client.admin().scripts().cancel(scripts[0].run_id)
    with contextlib.suppress(MppError):
        await running
