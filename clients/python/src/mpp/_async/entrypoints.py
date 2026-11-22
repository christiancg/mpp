from __future__ import annotations

from mpp._internal.client_context import ClientContext
from mpp._internal.descriptors import entrypoint
from mpp._internal.response_decoder import documents, number_of
from mpp.scripting_types import EntrypointDefinition, EntrypointInfo, EntrypointSaved


class AsyncEntrypoints:
    def __init__(self, context: ClientContext, project: str) -> None:
        self._context = context
        self._project = project

    async def save(self, definition: EntrypointDefinition) -> EntrypointSaved:
        reply = await self._context.channel.send(
            "SAVE_ENTRYPOINT", self._context.scripts.save_entrypoint(self._project, definition)
        )
        return EntrypointSaved(number_of(reply.get("version")) or 0, number_of(reply.get("procedureVersion")) or 0)

    async def delete(self, name: str) -> None:
        await self._context.channel.send(
            "DELETE_ENTRYPOINT", self._context.scripts.delete_entrypoint(self._project, name)
        )

    async def list(self) -> list[EntrypointInfo]:
        reply = await self._context.channel.send(
            "LIST_ENTRYPOINTS", self._context.scripts.list_entrypoints(self._project)
        )
        return [entrypoint(row) for row in documents(reply, "entrypoints")]
