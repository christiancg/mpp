import type { Status } from "../protocol/status.js";
import { errorCodeDetails, type ErrorCodeDetails } from "./error-codes.js";

export interface MppErrorOptions {
    readonly code?: string | undefined;
    readonly status?: Status | undefined;
    readonly operation?: string | undefined;
    readonly cause?: unknown;
}

export class MppError extends Error {
    readonly rawCode: string | undefined;
    readonly status: Status | undefined;
    readonly operation: string | undefined;

    constructor(message: string, options: MppErrorOptions = {}) {
        super(message, options.cause === undefined ? undefined : { cause: options.cause });
        this.name = new.target.name;
        this.rawCode = options.code;
        this.status = options.status;
        this.operation = options.operation;
    }

    get code(): ErrorCodeDetails | undefined {
        return errorCodeDetails(this.rawCode);
    }

    hasCode(code: string): boolean {
        return this.rawCode === code;
    }
}

export class AuthenticationError extends MppError {}

export class ForbiddenError extends MppError {}

export class NotFoundError extends MppError {}

export class ConflictError extends MppError {}

export class ListenEndedError extends MppError {}

export class UnavailableError extends MppError {}

export class UnknownOutcomeError extends MppError {}

export class ConnectionLostError extends MppError {}

export class RequestTimeoutError extends ConnectionLostError {}

export class ServerClosedConnectionError extends MppError {}

export class PoolExhaustedError extends MppError {}

export class MappingError extends MppError {}

export class FileChangedError extends MppError {}
