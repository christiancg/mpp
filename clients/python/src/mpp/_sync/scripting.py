from __future__ import annotations

from collections.abc import Mapping

from mpp._async.entrypoints import AsyncEntrypoints
from mpp._async.middlewares import AsyncMiddlewares
from mpp._async.procedures import AsyncProcedures
from mpp._async.schedules import AsyncSchedules
from mpp._internal.portal import SyncRuntime
from mpp.results import ScriptResult
from mpp.scripting_types import (
    EntrypointDefinition,
    EntrypointInfo,
    EntrypointSaved,
    MiddlewareDefinition,
    MiddlewareInfo,
    MiddlewareSaved,
    ProcedureDefinition,
    ProcedureInfo,
    ScheduleDefinition,
    ScheduleInfo,
)


class Procedures:
    def __init__(self, runtime: SyncRuntime, inner: AsyncProcedures) -> None:
        self._runtime = runtime
        self._inner = inner

    def save(self, definition: ProcedureDefinition) -> int:
        return self._runtime.run(self._inner.save(definition))

    def delete(self, name: str) -> None:
        self._runtime.run(self._inner.delete(name))

    def list(self, include_source: bool | None = None) -> list[ProcedureInfo]:
        return self._runtime.run(self._inner.list(include_source))

    def call(self, name: str, args: Mapping[str, object] | None = None) -> ScriptResult:
        return self._runtime.run(self._inner.call(name, args))


class Schedules:
    def __init__(self, runtime: SyncRuntime, inner: AsyncSchedules) -> None:
        self._runtime = runtime
        self._inner = inner

    def save(self, definition: ScheduleDefinition) -> int:
        return self._runtime.run(self._inner.save(definition))

    def delete(self, name: str) -> None:
        self._runtime.run(self._inner.delete(name))

    def list(self, include_args: bool | None = None) -> list[ScheduleInfo]:
        return self._runtime.run(self._inner.list(include_args))


class Entrypoints:
    def __init__(self, runtime: SyncRuntime, inner: AsyncEntrypoints) -> None:
        self._runtime = runtime
        self._inner = inner

    def save(self, definition: EntrypointDefinition) -> EntrypointSaved:
        return self._runtime.run(self._inner.save(definition))

    def delete(self, name: str) -> None:
        self._runtime.run(self._inner.delete(name))

    def list(self) -> list[EntrypointInfo]:
        return self._runtime.run(self._inner.list())


class Middlewares:
    def __init__(self, runtime: SyncRuntime, inner: AsyncMiddlewares) -> None:
        self._runtime = runtime
        self._inner = inner

    def save(self, definition: MiddlewareDefinition) -> MiddlewareSaved:
        return self._runtime.run(self._inner.save(definition))

    def delete(self, name: str) -> None:
        self._runtime.run(self._inner.delete(name))

    def list(self) -> list[MiddlewareInfo]:
        return self._runtime.run(self._inner.list())
