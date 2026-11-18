from __future__ import annotations

from typing import Generic, TypeVar

from mpp._async.query import AsyncQuery
from mpp._internal.collection_ops import QueryTarget
from mpp._internal.portal import SyncRuntime
from mpp.document import Document
from mpp.query.builder import QueryBuilder
from mpp.results import AnalyzeResult

R = TypeVar("R")


class SyncTarget(Generic[R]):
    __slots__ = ("inner", "runtime")

    def __init__(self, runtime: SyncRuntime, inner: QueryTarget[R]) -> None:
        self.runtime = runtime
        self.inner = inner


class Query(QueryBuilder[SyncTarget[R]], Generic[R]):
    __slots__ = ()

    def to_list(self) -> list[R]:
        return self._target.runtime.run(self._async().to_list())

    def to_documents(self) -> list[Document]:
        return self._target.runtime.run(self._async().to_documents())

    def first(self) -> R | None:
        return self._target.runtime.run(self._async().first())

    def count(self) -> int:
        return self._target.runtime.run(self._async().count())

    def analyze(self) -> AnalyzeResult:
        return self._target.runtime.run(self._async().analyze())

    def _async(self) -> AsyncQuery[R]:
        return AsyncQuery(self._target.inner, self._steps, self._dirty)
