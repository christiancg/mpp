import type { Document } from "../document.js";

export const TriggerEvent = { CREATED: "CREATED", UPDATED: "UPDATED", DELETED: "DELETED" } as const;
export type TriggerEvent = (typeof TriggerEvent)[keyof typeof TriggerEvent];

export const TriggerTiming = { AFTER: "after", BEFORE: "before" } as const;
export type TriggerTiming = (typeof TriggerTiming)[keyof typeof TriggerTiming];

export const TriggerMode = { DOCUMENT: "document", BATCH: "batch" } as const;
export type TriggerMode = (typeof TriggerMode)[keyof typeof TriggerMode];

export const EntrypointMethod = {
    GET: "GET",
    POST: "POST",
    PUT: "PUT",
    PATCH: "PATCH",
    DELETE: "DELETE",
    OPTIONS: "OPTIONS",
    QUERY: "QUERY",
} as const;
export type EntrypointMethod = (typeof EntrypointMethod)[keyof typeof EntrypointMethod];

export const MiddlewarePhase = { INGRESS: "INGRESS", EGRESS: "EGRESS" } as const;
export type MiddlewarePhase = (typeof MiddlewarePhase)[keyof typeof MiddlewarePhase];

export interface ProcedureDefinition {
    readonly name: string;
    readonly script: string;
    readonly description?: string;
    readonly enabled?: boolean;
    readonly ifVersion?: number;
}

export interface TriggerDefinition {
    readonly name: string;
    readonly procedureName: string;
    readonly events: readonly TriggerEvent[];
    readonly timing?: TriggerTiming;
    readonly mode?: TriggerMode;
    readonly allowCascade?: boolean;
    readonly enabled?: boolean;
    readonly ifVersion?: number;
}

export interface ScheduleDefinition {
    readonly name: string;
    readonly procedureName: string;
    readonly cron?: string;
    readonly intervalMs?: number;
    readonly args?: Document;
    readonly timeoutMs?: number;
    readonly description?: string;
    readonly enabled?: boolean;
    readonly ifVersion?: number;
}

export interface EntrypointDefinition {
    readonly name: string;
    readonly method: EntrypointMethod;
    readonly path: string;
    readonly procedureName?: string;
    readonly procedure?: ProcedureDefinition;
    readonly timeoutMs?: number;
    readonly description?: string;
    readonly enabled?: boolean;
    readonly ifVersion?: number;
}

export interface MiddlewareRule {
    readonly methods?: readonly EntrypointMethod[];
    readonly pathPrefix?: string;
    readonly entrypoints?: readonly string[];
}

export interface MiddlewareMatch extends MiddlewareRule {
    readonly exclude?: readonly MiddlewareRule[];
}

export interface MiddlewareDefinition {
    readonly name: string;
    readonly phase: MiddlewarePhase;
    readonly order?: number;
    readonly match?: MiddlewareMatch;
    readonly procedureName?: string;
    readonly procedure?: ProcedureDefinition;
    readonly timeoutMs?: number;
    readonly description?: string;
    readonly enabled?: boolean;
    readonly ifVersion?: number;
}

export interface ProcedureInfo {
    readonly name: string;
    readonly version: number;
    readonly enabled: boolean;
    readonly description: string | undefined;
    readonly source: string | undefined;
    readonly raw: Document;
}

export interface TriggerInfo {
    readonly name: string;
    readonly collection: string | undefined;
    readonly events: readonly string[];
    readonly procedureName: string;
    readonly timing: string;
    readonly mode: string;
    readonly enabled: boolean;
    readonly version: number;
    readonly definer: string | undefined;
    readonly raw: Document;
}

export interface ScheduleInfo {
    readonly name: string;
    readonly procedureName: string;
    readonly cron: string | undefined;
    readonly intervalMs: number | undefined;
    readonly enabled: boolean;
    readonly version: number;
    readonly definer: string | undefined;
    readonly nextRunAt: number | undefined;
    readonly raw: Document;
}

export interface EntrypointInfo {
    readonly name: string;
    readonly method: string;
    readonly path: string;
    readonly url: string;
    readonly procedureName: string;
    readonly timeoutMs: number;
    readonly enabled: boolean;
    readonly version: number;
    readonly definer: string | undefined;
    readonly description: string | undefined;
    readonly disabledReason: string | undefined;
    readonly raw: Document;
}

export interface EntrypointSaved {
    readonly version: number;
    readonly procedureVersion: number;
}

export interface MiddlewareInfo {
    readonly name: string;
    readonly phase: string;
    readonly order: number;
    readonly match: MiddlewareMatch | undefined;
    readonly procedureName: string;
    readonly timeoutMs: number;
    readonly enabled: boolean;
    readonly version: number;
    readonly definer: string | undefined;
    readonly description: string | undefined;
    readonly disabledReason: string | undefined;
    readonly raw: Document;
}

export interface MiddlewareSaved {
    readonly version: number;
    readonly procedureVersion: number;
}

export interface TriggerSaved {
    readonly version: number;
    readonly definer: string;
}

export interface TriggerTestResult {
    readonly decision: string;
    readonly document: Document | undefined;
    readonly logs: readonly string[];
    readonly logsTruncated: boolean;
}
