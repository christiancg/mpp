from __future__ import annotations

from dataclasses import dataclass
from typing import Literal, cast

from mpp._catalogue import OPERATIONS as _CATALOGUE
from mpp._catalogue import Operation

__all__ = ["OPERATIONS", "Operation", "OperationDetails", "OperationScope", "operation_details"]

OperationScope = Literal["PROJECT", "COLLECTION", "OPTIONAL_COLLECTION", "NONE"]

_READS = frozenset(
    {
        Operation.FIND_BY_ID,
        Operation.AGGREGATE,
        Operation.LIST_PROJECTS,
        Operation.LIST_COLLECTIONS,
        Operation.GET_SCHEMA,
        Operation.SERVER_INFO,
        Operation.LIST_USERS,
        Operation.GET_PROJECT_STATS,
        Operation.LIST_TRANSACTIONS,
        Operation.LIST_PROCEDURES,
        Operation.LIST_TRIGGERS,
        Operation.LIST_SCHEDULES,
        Operation.LIST_ENTRYPOINTS,
        Operation.LIST_MIDDLEWARES,
        Operation.LIST_SCRIPTS,
        Operation.LIST_TRIGGER_RUNS,
        Operation.READ_FILE,
        Operation.STAT_FILE,
        Operation.LIST_FILES,
    }
)


@dataclass(frozen=True, slots=True)
class OperationDetails:
    name: str
    scope: OperationScope
    admin_only: bool
    allowed_in_transaction: bool
    read: bool


OPERATIONS: tuple[OperationDetails, ...] = tuple(
    OperationDetails(name, cast("OperationScope", scope), admin_only, allowed, name in _READS)
    for name, scope, admin_only, allowed in _CATALOGUE
)

_BY_NAME = {details.name: details for details in OPERATIONS}


def operation_details(operation: str) -> OperationDetails:
    details = _BY_NAME.get(operation)
    if details is None:
        raise ValueError(f"unknown operation {operation}")
    return details
