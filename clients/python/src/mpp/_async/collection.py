from __future__ import annotations

from collections.abc import Sequence
from typing import Generic, TypeVar

from mpp._async.indexes import AsyncIndexes
from mpp._async.query import AsyncQuery
from mpp._async.schema import AsyncSchema
from mpp._async.subscription import AsyncSubscription
from mpp._async.triggers import AsyncTriggers
from mpp._internal.collection_ops import CollectionOps
from mpp._internal.handler_adapter import Callbacks, async_callbacks
from mpp.document import Document
from mpp.errors import MppError
from mpp.listen import Handler
from mpp.query.types import Filter, PipelineStep
from mpp.results import BulkSaveResult, SaveResult

T = TypeVar("T")
R = TypeVar("R")


class AsyncCollection(Generic[T, R]):
    def __init__(self, ops: CollectionOps[T, R]) -> None:
        self._ops = ops

    @property
    def database_name(self) -> str:
        return self._ops.database

    @property
    def name(self) -> str:
        return self._ops.collection

    async def create(self) -> None:
        context = self._ops.context
        await context.channel.send(
            "CREATE_COLLECTION", context.encoder.create_collection(self._ops.database, self._ops.collection)
        )

    async def drop(self) -> None:
        context = self._ops.context
        await context.channel.send(
            "DROP_COLLECTION", context.encoder.drop_collection(self._ops.database, self._ops.collection)
        )

    async def save(self, value: T) -> SaveResult:
        return await self._ops.save(value)

    async def insert(self, value: T) -> SaveResult:
        return await self._ops.insert(value)

    async def save_all(self, values: Sequence[T]) -> BulkSaveResult:
        return await self._ops.save_all(values)

    async def find_by_id(self, id: str, *, dirty_read: bool = False) -> R | None:  # noqa: A002
        return await self._ops.find_by_id(id, dirty_read)

    async def update_where(self, condition: Filter, patch: Document) -> int:
        return await self._ops.update_where(condition, patch)

    async def delete(self, id: str) -> None:  # noqa: A002
        await self._ops.delete(id)

    async def delete_where(self, condition: Filter) -> int:
        return await self._ops.delete_where(condition)

    def query(self) -> AsyncQuery[R]:
        return AsyncQuery(self._ops)

    def indexes(self) -> AsyncIndexes:
        return AsyncIndexes(self._ops.context, self._ops.database, self._ops.collection)

    def schema(self) -> AsyncSchema:
        return AsyncSchema(self._ops.context, self._ops.database, self._ops.collection)

    def triggers(self) -> AsyncTriggers:
        return AsyncTriggers(self._ops.context, self._ops.database, self._ops.collection)

    async def listen(self, handler: Handler[R], query: AsyncQuery[R] | None = None) -> AsyncSubscription:
        return await self._listen(async_callbacks(handler), () if query is None else query.steps)

    async def _listen(self, callbacks: Callbacks, steps: Sequence[PipelineStep]) -> AsyncSubscription:
        listens = self._ops.context.listens
        if listens is None:
            raise MppError("listens are not available here")
        return await listens.listen(self._ops.database, self._ops.collection, steps, self._ops.decode, callbacks)
