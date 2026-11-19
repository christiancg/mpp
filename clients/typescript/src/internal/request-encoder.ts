import type { Filter, PipelineStep } from "../query/types.js";
import { PipelineEncoder } from "./pipeline-encoder.js";
import { encodeDocument, type EncodeOptions, type JsonObject } from "./values-codec.js";

export function request(type: string, project?: string, collection?: string): JsonObject {
    return {
        type,
        ...(project === undefined ? {} : { projectName: project }),
        ...(collection === undefined ? {} : { collectionName: collection }),
    };
}

function dirty(frame: JsonObject, dirtyRead: boolean): JsonObject {
    return dirtyRead ? { ...frame, dirtyRead: true } : frame;
}

export class RequestEncoder {
    readonly pipeline: PipelineEncoder;
    readonly #options: EncodeOptions;

    constructor(options: EncodeOptions = {}) {
        this.#options = options;
        this.pipeline = new PipelineEncoder(options);
    }

    document(value: unknown): JsonObject {
        return encodeDocument(value, this.#options);
    }

    authenticate(username: string, password: string): JsonObject {
        return { ...request("AUTHENTICATE"), username, password };
    }

    save(project: string, collection: string, document: JsonObject): JsonObject {
        return { ...request("SAVE", project, collection), object: document };
    }

    insert(project: string, collection: string, document: JsonObject): JsonObject {
        return { ...this.save(project, collection, document), failOnUpdate: true };
    }

    bulkSave(project: string, collection: string, documents: JsonObject[]): JsonObject {
        return { ...request("BULK_SAVE", project, collection), objects: documents };
    }

    findById(project: string, collection: string, id: string, dirtyRead: boolean): JsonObject {
        return dirty({ ...request("FIND_BY_ID", project, collection), _id: id }, dirtyRead);
    }

    delete(project: string, collection: string, id: string): JsonObject {
        return { ...request("DELETE", project, collection), _id: id };
    }

    deleteWhere(project: string, collection: string, condition: Filter): JsonObject {
        return { ...request("DELETE", project, collection), condition: this.pipeline.filter(condition) };
    }

    updateWhere(project: string, collection: string, condition: Filter, patch: JsonObject): JsonObject {
        return { ...request("SAVE", project, collection), condition: this.pipeline.filter(condition), object: patch };
    }

    aggregate(
        project: string,
        collection: string,
        steps: readonly PipelineStep[],
        dirtyRead: boolean,
        analyze: boolean,
    ): JsonObject {
        const frame = { ...request("AGGREGATE", project, collection), aggregationSteps: this.pipeline.steps(steps) };
        return dirty(analyze ? { ...frame, analyze: true } : frame, dirtyRead);
    }

    listen(project: string, collection: string, steps: readonly PipelineStep[]): JsonObject {
        return { ...request("LISTEN", project, collection), aggregationSteps: this.pipeline.steps(steps) };
    }

    stopListen(listenId: string): JsonObject {
        return { ...request("STOP_LISTEN"), listenId };
    }

    listProjects(): JsonObject {
        return request("LIST_PROJECTS");
    }

    createProject(project: string): JsonObject {
        return request("CREATE_PROJECT", project);
    }

    dropProject(project: string): JsonObject {
        return request("DROP_PROJECT", project);
    }

    listCollections(project: string): JsonObject {
        return request("LIST_COLLECTIONS", project);
    }

    createCollection(project: string, collection: string): JsonObject {
        return request("CREATE_COLLECTION", project, collection);
    }

    dropCollection(project: string, collection: string): JsonObject {
        return request("DROP_COLLECTION", project, collection);
    }

    createIndex(project: string, collection: string, fields: readonly string[]): JsonObject {
        const frame = request("CREATE_INDEX", project, collection);
        const [only] = fields;
        return fields.length === 1 && only !== undefined
            ? { ...frame, fieldName: only }
            : { ...frame, fieldNames: [...fields] };
    }

    dropIndex(project: string, collection: string, field: string): JsonObject {
        return { ...request("DROP_INDEX", project, collection), fieldName: field };
    }

    reindex(project: string, collection: string, fields: readonly string[]): JsonObject {
        const frame = request("REINDEX", project, collection);
        return fields.length === 0 ? frame : { ...frame, fieldNames: [...fields] };
    }

    reindexPrimaryKey(project: string, collection: string): JsonObject {
        return { ...request("REINDEX", project, collection), primaryKey: true };
    }

    saveSchema(project: string, collection: string, schema: JsonObject): JsonObject {
        return { ...request("SAVE_SCHEMA", project, collection), schema };
    }

    getSchema(project: string, collection: string): JsonObject {
        return request("GET_SCHEMA", project, collection);
    }

    deleteSchema(project: string, collection: string): JsonObject {
        return request("DELETE_SCHEMA", project, collection);
    }

    startTransaction(): JsonObject {
        return request("START_TRANSACTION");
    }

    commitTransaction(): JsonObject {
        return request("COMMIT_TRANSACTION");
    }

    rollbackTransaction(): JsonObject {
        return request("ROLLBACK_TRANSACTION");
    }

    serverInfo(): JsonObject {
        return request("SERVER_INFO");
    }

    projectStats(project?: string): JsonObject {
        return request("GET_PROJECT_STATS", project);
    }

    closeConnection(): JsonObject {
        return request("CLOSE_CONNECTION");
    }

    setProjectOwners(project: string, owners: readonly string[]): JsonObject {
        return { ...request("SET_PROJECT_OWNERS", project), owners: [...owners] };
    }
}
