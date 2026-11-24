from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from enum import StrEnum

from mpp.document import Document


class TriggerEvent(StrEnum):
    CREATED = "CREATED"
    UPDATED = "UPDATED"
    DELETED = "DELETED"


class TriggerTiming(StrEnum):
    AFTER = "after"
    BEFORE = "before"


class TriggerMode(StrEnum):
    DOCUMENT = "document"
    BATCH = "batch"


class EntrypointMethod(StrEnum):
    GET = "GET"
    POST = "POST"
    PUT = "PUT"
    PATCH = "PATCH"
    DELETE = "DELETE"
    OPTIONS = "OPTIONS"
    QUERY = "QUERY"


class MiddlewarePhase(StrEnum):
    INGRESS = "INGRESS"
    EGRESS = "EGRESS"


@dataclass(frozen=True, slots=True)
class ProcedureDefinition:
    name: str
    script: str
    description: str | None = None
    enabled: bool | None = None
    if_version: int | None = None


@dataclass(frozen=True, slots=True)
class TriggerDefinition:
    name: str
    procedure_name: str
    events: Sequence[TriggerEvent]
    timing: TriggerTiming | None = None
    mode: TriggerMode | None = None
    allow_cascade: bool | None = None
    enabled: bool | None = None
    if_version: int | None = None


@dataclass(frozen=True, slots=True)
class ScheduleDefinition:
    name: str
    procedure_name: str
    cron: str | None = None
    interval_ms: int | None = None
    args: Document | None = None
    timeout_ms: int | None = None
    description: str | None = None
    enabled: bool | None = None
    if_version: int | None = None


@dataclass(frozen=True, slots=True)
class EntrypointDefinition:
    name: str
    method: EntrypointMethod
    path: str
    procedure_name: str | None = None
    procedure: ProcedureDefinition | None = None
    timeout_ms: int | None = None
    description: str | None = None
    enabled: bool | None = None
    if_version: int | None = None


@dataclass(frozen=True, slots=True)
class MiddlewareRule:
    methods: Sequence[EntrypointMethod] | None = None
    path_prefix: str | None = None
    entrypoints: Sequence[str] | None = None


@dataclass(frozen=True, slots=True)
class MiddlewareMatch:
    methods: Sequence[EntrypointMethod] | None = None
    path_prefix: str | None = None
    entrypoints: Sequence[str] | None = None
    exclude: Sequence[MiddlewareRule] | None = None


@dataclass(frozen=True, slots=True)
class MiddlewareDefinition:
    name: str
    phase: MiddlewarePhase
    procedure_name: str | None = None
    procedure: ProcedureDefinition | None = None
    order: int | None = None
    match: MiddlewareMatch | None = None
    timeout_ms: int | None = None
    description: str | None = None
    enabled: bool | None = None
    if_version: int | None = None


@dataclass(frozen=True, slots=True)
class ProcedureInfo:
    name: str
    version: float
    enabled: bool
    description: str | None
    source: str | None
    raw: Document


@dataclass(frozen=True, slots=True)
class TriggerInfo:
    name: str
    collection: str | None
    events: tuple[str, ...]
    procedure_name: str
    timing: str
    mode: str
    enabled: bool
    version: float
    definer: str | None
    raw: Document


@dataclass(frozen=True, slots=True)
class ScheduleInfo:
    name: str
    procedure_name: str
    cron: str | None
    interval_ms: float | None
    enabled: bool
    version: float
    definer: str | None
    next_run_at: float | None
    raw: Document


@dataclass(frozen=True, slots=True)
class EntrypointInfo:
    name: str
    method: str
    path: str
    url: str
    procedure_name: str
    timeout_ms: float
    enabled: bool
    version: float
    definer: str | None
    description: str | None
    disabled_reason: str | None
    raw: Document


@dataclass(frozen=True, slots=True)
class EntrypointSaved:
    version: float
    procedure_version: float


@dataclass(frozen=True, slots=True)
class MiddlewareInfo:
    name: str
    phase: str
    order: float
    match: MiddlewareMatch | None
    procedure_name: str
    timeout_ms: float
    enabled: bool
    version: float
    definer: str | None
    description: str | None
    disabled_reason: str | None
    raw: Document


@dataclass(frozen=True, slots=True)
class MiddlewareSaved:
    version: float
    procedure_version: float


@dataclass(frozen=True, slots=True)
class TriggerSaved:
    version: float
    definer: str


@dataclass(frozen=True, slots=True)
class TriggerTestResult:
    decision: str
    document: Document | None
    logs: tuple[str, ...]
    logs_truncated: bool
