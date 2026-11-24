from __future__ import annotations

from mpp._internal.client_context import ClientContext
from mpp._internal.descriptors import middleware
from mpp._internal.response_decoder import documents, number_of
from mpp.scripting_types import MiddlewareDefinition, MiddlewareInfo, MiddlewareSaved


class AsyncMiddlewares:
    def __init__(self, context: ClientContext, project: str) -> None:
        self._context = context
        self._project = project

    async def save(self, definition: MiddlewareDefinition) -> MiddlewareSaved:
        reply = await self._context.channel.send(
            "SAVE_MIDDLEWARE", self._context.scripts.save_middleware(self._project, definition)
        )
        return MiddlewareSaved(number_of(reply.get("version")) or 0, number_of(reply.get("procedureVersion")) or 0)

    async def delete(self, name: str) -> None:
        await self._context.channel.send(
            "DELETE_MIDDLEWARE", self._context.scripts.delete_middleware(self._project, name)
        )

    async def list(self) -> list[MiddlewareInfo]:
        reply = await self._context.channel.send(
            "LIST_MIDDLEWARES", self._context.scripts.list_middlewares(self._project)
        )
        return [middleware(row) for row in documents(reply, "middlewares")]
