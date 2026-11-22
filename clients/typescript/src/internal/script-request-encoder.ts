import type { Permissions, TransactionDecision, TriggerRunDecision, TriggerRunStatus } from "../admin/types.js";
import type { PipelineStep } from "../query/types.js";
import type {
    EntrypointDefinition,
    ProcedureDefinition,
    ScheduleDefinition,
    TriggerDefinition,
    TriggerEvent,
} from "../scripting/definitions.js";
import { request, type RequestEncoder } from "./request-encoder.js";
import type { Json, JsonObject } from "./values-codec.js";

function optional(field: string, value: Json | undefined): JsonObject {
    return value === undefined ? {} : { [field]: value };
}

function permissions(granted: Permissions): JsonObject {
    return {
        admin: granted.admin ?? false,
        globalPermissions: [...(granted.global ?? [])],
        projectPermissions: { ...granted.projects },
        collectionPermissions: { ...granted.collections },
        scriptPermissions: { ...granted.scripts },
    };
}

function checkSchedule(schedule: ScheduleDefinition): void {
    if ((schedule.cron === undefined) === (schedule.intervalMs === undefined)) {
        throw new TypeError("a schedule takes exactly one of cron and intervalMs");
    }
}

function checkEntrypoint(entrypoint: EntrypointDefinition): void {
    if ((entrypoint.procedureName === undefined) === (entrypoint.procedure === undefined)) {
        throw new TypeError("an entrypoint takes exactly one of procedureName and procedure");
    }
    if (entrypoint.procedure?.ifVersion !== undefined) {
        throw new TypeError("an inline procedure takes no ifVersion");
    }
}

function inlineProcedure(procedure: ProcedureDefinition | undefined): JsonObject {
    return procedure === undefined
        ? {}
        : {
              procedure: {
                  name: procedure.name,
                  script: procedure.script,
                  ...optional("description", procedure.description),
                  ...optional("enabled", procedure.enabled),
              },
          };
}

function checkTrigger(trigger: TriggerDefinition): void {
    if (trigger.events.length === 0) {
        throw new RangeError("a trigger needs at least one event");
    }
}

export class ScriptRequestEncoder {
    readonly #encoder: RequestEncoder;

    constructor(encoder: RequestEncoder) {
        this.#encoder = encoder;
    }

    runScript(project: string, script: string, args: unknown): JsonObject {
        return { ...request("RUN_SCRIPT", project), script, ...this.#args(args) };
    }

    saveProcedure(project: string, procedure: ProcedureDefinition): JsonObject {
        return {
            ...request("SAVE_PROCEDURE", project),
            name: procedure.name,
            script: procedure.script,
            ...optional("description", procedure.description),
            ...optional("enabled", procedure.enabled),
            ...optional("ifVersion", procedure.ifVersion),
        };
    }

    deleteProcedure(project: string, name: string): JsonObject {
        return { ...request("DELETE_PROCEDURE", project), name };
    }

    listProcedures(project: string, includeSource: boolean | undefined): JsonObject {
        return { ...request("LIST_PROCEDURES", project), ...optional("includeSource", includeSource) };
    }

    callProcedure(project: string, name: string, args: unknown): JsonObject {
        return { ...request("CALL_PROCEDURE", project), procedureName: name, ...this.#args(args) };
    }

    saveTrigger(project: string, collection: string, trigger: TriggerDefinition): JsonObject {
        checkTrigger(trigger);
        return {
            ...request("SAVE_TRIGGER", project, collection),
            name: trigger.name,
            events: [...trigger.events],
            procedureName: trigger.procedureName,
            ...optional("timing", trigger.timing),
            ...optional("mode", trigger.mode),
            ...optional("allowCascade", trigger.allowCascade),
            ...optional("enabled", trigger.enabled),
            ...optional("ifVersion", trigger.ifVersion),
        };
    }

    deleteTrigger(project: string, collection: string, name: string): JsonObject {
        return { ...request("DELETE_TRIGGER", project, collection), name };
    }

    listTriggers(project: string, collection: string | undefined): JsonObject {
        return request("LIST_TRIGGERS", project, collection);
    }

    testTrigger(project: string, collection: string, name: string, event: TriggerEvent, document: unknown): JsonObject {
        return {
            ...request("TEST_TRIGGER", project, collection),
            name,
            event,
            document: this.#encoder.document(document),
        };
    }

    saveSchedule(project: string, schedule: ScheduleDefinition): JsonObject {
        checkSchedule(schedule);
        return {
            ...request("SAVE_SCHEDULE", project),
            name: schedule.name,
            procedureName: schedule.procedureName,
            ...optional("cron", schedule.cron),
            ...optional("intervalMs", schedule.intervalMs),
            ...this.#args(schedule.args),
            ...optional("timeoutMs", schedule.timeoutMs),
            ...optional("description", schedule.description),
            ...optional("enabled", schedule.enabled),
            ...optional("ifVersion", schedule.ifVersion),
        };
    }

    deleteSchedule(project: string, name: string): JsonObject {
        return { ...request("DELETE_SCHEDULE", project), name };
    }

    listSchedules(project: string, includeArgs: boolean | undefined): JsonObject {
        return { ...request("LIST_SCHEDULES", project), ...optional("includeArgs", includeArgs) };
    }

    saveEntrypoint(project: string, entrypoint: EntrypointDefinition): JsonObject {
        checkEntrypoint(entrypoint);
        return {
            ...request("SAVE_ENTRYPOINT", project),
            name: entrypoint.name,
            method: entrypoint.method,
            path: entrypoint.path,
            ...optional("procedureName", entrypoint.procedureName),
            ...inlineProcedure(entrypoint.procedure),
            ...optional("timeoutMs", entrypoint.timeoutMs),
            ...optional("description", entrypoint.description),
            ...optional("enabled", entrypoint.enabled),
            ...optional("ifVersion", entrypoint.ifVersion),
        };
    }

    deleteEntrypoint(project: string, name: string): JsonObject {
        return { ...request("DELETE_ENTRYPOINT", project), name };
    }

    listEntrypoints(project: string): JsonObject {
        return request("LIST_ENTRYPOINTS", project);
    }

    listScripts(): JsonObject {
        return request("LIST_SCRIPTS");
    }

    cancelScript(runId: string): JsonObject {
        return { ...request("CANCEL_SCRIPT"), runId };
    }

    listTriggerRuns(status: TriggerRunStatus | undefined): JsonObject {
        return { ...request("LIST_TRIGGER_RUNS"), ...optional("status", status) };
    }

    resolveTriggerRun(runId: string, decision: TriggerRunDecision): JsonObject {
        return { ...request("RESOLVE_TRIGGER_RUN"), runId, decision };
    }

    listTransactions(): JsonObject {
        return request("LIST_TRANSACTIONS");
    }

    resolveTransaction(dtxId: string, decision: TransactionDecision): JsonObject {
        return { ...request("RESOLVE_TRANSACTION"), dtxId, decision };
    }

    createUser(username: string, password: string, granted: Permissions | undefined): JsonObject {
        return {
            ...request("CREATE_USER"),
            username,
            password,
            ...(granted === undefined ? {} : permissions(granted)),
        };
    }

    deleteUser(username: string): JsonObject {
        return { ...request("DELETE_USER"), username };
    }

    changePermissions(username: string, granted: Permissions): JsonObject {
        return { ...request("CHANGE_PERMISSIONS"), username, ...permissions(granted) };
    }

    setPassword(username: string, currentPassword: string | undefined, newPassword: string): JsonObject {
        return { ...request("SET_PASSWORD"), username, ...optional("currentPassword", currentPassword), newPassword };
    }

    listUsers(steps: readonly PipelineStep[]): JsonObject {
        return steps.length === 0
            ? request("LIST_USERS")
            : { ...request("LIST_USERS"), aggregationSteps: this.#encoder.pipeline.steps(steps) };
    }

    #args(args: unknown): JsonObject {
        return args === undefined ? {} : { args: this.#encoder.document(args) };
    }
}
