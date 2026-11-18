from __future__ import annotations

from mpp.error_codes import ErrorCodeDetails, error_code_details
from mpp.status import Status


class MppError(Exception):
    def __init__(
        self,
        message: str,
        *,
        code: str | None = None,
        status: Status | None = None,
        operation: str | None = None,
    ) -> None:
        super().__init__(message)
        self.message = message
        self.raw_code = code
        self.status = status
        self.operation = operation

    @property
    def code(self) -> ErrorCodeDetails | None:
        return error_code_details(self.raw_code)

    def has_code(self, code: str) -> bool:
        return self.raw_code == code


class AuthenticationError(MppError):
    pass


class ForbiddenError(MppError):
    pass


class NotFoundError(MppError):
    pass


class ConflictError(MppError):
    pass


class ListenEndedError(MppError):
    pass


class UnavailableError(MppError):
    pass


class UnknownOutcomeError(MppError):
    pass


class ConnectionLostError(MppError):
    pass


class RequestTimeoutError(ConnectionLostError):
    pass


class ServerClosedConnectionError(MppError):
    pass


class PoolExhaustedError(MppError):
    pass


class MappingError(MppError):
    pass
