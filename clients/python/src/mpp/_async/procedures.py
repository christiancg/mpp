from __future__ import annotations

from collections.abc import Mapping

from mpp._internal.client_context import ClientContext
from mpp._internal.descriptors import procedure, script_result
from mpp._internal.response_decoder import documents, number_of
from mpp.results import ScriptResult
from mpp.scripting_types import ProcedureDefinition, ProcedureInfo


class AsyncProcedures:
    def __init__(self, context: ClientContext, project: str) -> None:
        self._context = context
        self._project = project

    async def save(self, definition: ProcedureDefinition) -> int:
        reply = await self._context.channel.send(
            "SAVE_PROCEDURE", self._context.scripts.save_procedure(self._project, definition)
        )
        return int(number_of(reply.get("version")) or 0)

    async def delete(self, name: str) -> None:
        await self._context.channel.send(
            "DELETE_PROCEDURE", self._context.scripts.delete_procedure(self._project, name)
        )

    async def list(self, include_source: bool | None = None) -> list[ProcedureInfo]:
        reply = await self._context.channel.send(
            "LIST_PROCEDURES", self._context.scripts.list_procedures(self._project, include_source)
        )
        return [procedure(row) for row in documents(reply, "procedures")]

    async def call(self, name: str, args: Mapping[str, object] | None = None) -> ScriptResult:
        return script_result(
            await self._context.channel.send(
                "CALL_PROCEDURE", self._context.scripts.call_procedure(self._project, name, args)
            )
        )
