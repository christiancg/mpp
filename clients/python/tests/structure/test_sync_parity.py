from __future__ import annotations

import inspect
import re

import pytest

import mpp

PAIRS: list[tuple[type, type]] = [
    (mpp.AsyncMppClient, mpp.MppClient),
    (mpp.AsyncProject, mpp.Project),
    (mpp.AsyncCollection, mpp.Collection),
    (mpp.AsyncQuery, mpp.Query),
    (mpp.AsyncTransaction, mpp.Transaction),
    (mpp.AsyncTransactionCollection, mpp.TransactionCollection),
    (mpp.AsyncIndexes, mpp.Indexes),
    (mpp.AsyncSchema, mpp.Schema),
    (mpp.AsyncTriggers, mpp.Triggers),
    (mpp.AsyncProcedures, mpp.Procedures),
    (mpp.AsyncSchedules, mpp.Schedules),
    (mpp.AsyncAdmin, mpp.Admin),
    (mpp.AsyncUsers, mpp.Users),
    (mpp.AsyncScripts, mpp.Scripts),
    (mpp.AsyncTriggerRuns, mpp.TriggerRuns),
    (mpp.AsyncDistributedTransactions, mpp.DistributedTransactions),
    (mpp.AsyncSubscription, mpp.Subscription),
]

CONTEXT_MANAGERS = {"__aenter__": "__enter__", "__aexit__": "__exit__"}


def _sync_text(annotation: str) -> str:
    text = annotation.replace("AbstractAsyncContextManager", "AbstractContextManager")
    return re.sub(r"\bAsync(?=[A-Z])", "", text)


def _members(cls: type) -> dict[str, object]:
    found = {}
    for name in dir(cls):
        if name in CONTEXT_MANAGERS or name in CONTEXT_MANAGERS.values():
            found[CONTEXT_MANAGERS.get(name, name)] = getattr(cls, name)
        elif not name.startswith("_"):
            found[name] = inspect.getattr_static(cls, name)
    return found


def shape(member: object) -> str:
    if isinstance(member, property):
        return "property"
    function = member.__func__ if isinstance(member, classmethod | staticmethod) else member
    if not callable(function):
        return "attribute"
    signature = inspect.signature(function)
    parameters = [
        f"{parameter.name}:{parameter.kind.name}:{_sync_text(str(parameter.annotation))}:{parameter.default!r}"
        for parameter in signature.parameters.values()
        if parameter.name not in {"self", "cls"}
    ]
    prefix = "async " if inspect.iscoroutinefunction(function) else ""
    return f"{prefix}({', '.join(parameters)}) -> {_sync_text(str(signature.return_annotation))}"


def mismatches(async_class: type, sync_class: type) -> list[str]:
    async_members = _members(async_class)
    sync_members = _members(sync_class)
    problems = [f"{name} exists on one side only" for name in sorted(set(async_members) ^ set(sync_members))]
    for name in sorted(set(async_members) & set(sync_members)):
        expected = shape(async_members[name]).removeprefix("async ")
        actual = shape(sync_members[name])
        if expected != actual:
            problems.append(f"{name}\n  async {expected}\n  sync  {actual}")
    return problems


@pytest.mark.parametrize(("async_class", "sync_class"), PAIRS, ids=[pair[1].__name__ for pair in PAIRS])
def test_the_sync_class_mirrors_the_async_class(async_class: type, sync_class: type) -> None:
    assert mismatches(async_class, sync_class) == []


def test_every_async_class_has_a_sync_twin() -> None:
    exported = {name for name in mpp.__all__ if name.startswith("Async")}
    assert exported == {pair[0].__name__ for pair in PAIRS}
