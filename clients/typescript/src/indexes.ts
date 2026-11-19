import type { ClientContext } from "./internal/client-context.js";
import { numberOf, strings } from "./internal/response-decoder.js";
import type { JsonObject } from "./internal/values-codec.js";
import type { ReindexResult } from "./results.js";

function result(reply: JsonObject): ReindexResult {
    return {
        rebuiltFields: strings(reply.rebuiltFields),
        primaryKeyEntries: numberOf(reply.primaryKeyEntries),
        unversionedEntries: numberOf(reply.unversionedEntries),
    };
}

export class Indexes {
    readonly #context: ClientContext;
    readonly #project: string;
    readonly #collection: string;

    constructor(context: ClientContext, project: string, collection: string) {
        this.#context = context;
        this.#project = project;
        this.#collection = collection;
    }

    async create(field: string, ...moreFields: string[]): Promise<void> {
        await this.#context.channel.send(
            "CREATE_INDEX",
            this.#context.encoder.createIndex(this.#project, this.#collection, [field, ...moreFields]),
        );
    }

    async drop(field: string): Promise<void> {
        await this.#context.channel.send(
            "DROP_INDEX",
            this.#context.encoder.dropIndex(this.#project, this.#collection, field),
        );
    }

    async reindex(...fields: string[]): Promise<ReindexResult> {
        return result(
            await this.#context.channel.send(
                "REINDEX",
                this.#context.encoder.reindex(this.#project, this.#collection, fields),
            ),
        );
    }

    async reindexPrimaryKey(): Promise<ReindexResult> {
        return result(
            await this.#context.channel.send(
                "REINDEX",
                this.#context.encoder.reindexPrimaryKey(this.#project, this.#collection),
            ),
        );
    }
}
