import type { Document } from "./document.js";
import { NotFoundError } from "./errors/errors.js";
import type { ClientContext } from "./internal/client-context.js";
import { document, strings } from "./internal/response-decoder.js";

export class Schema {
    readonly #context: ClientContext;
    readonly #project: string;
    readonly #collection: string;

    constructor(context: ClientContext, project: string, collection: string) {
        this.#context = context;
        this.#project = project;
        this.#collection = collection;
    }

    async save(schema: Document): Promise<string[]> {
        const encoder = this.#context.encoder;
        const reply = await this.#context.channel.send(
            "SAVE_SCHEMA",
            encoder.saveSchema(this.#project, this.#collection, encoder.document(schema)),
        );
        return strings(reply.warnings);
    }

    async get(): Promise<Document | undefined> {
        try {
            const reply = await this.#context.channel.send(
                "GET_SCHEMA",
                this.#context.encoder.getSchema(this.#project, this.#collection),
            );
            return document(reply, "schema");
        } catch (error) {
            if (error instanceof NotFoundError) {
                return undefined;
            }
            throw error;
        }
    }

    async delete(): Promise<void> {
        await this.#context.channel.send(
            "DELETE_SCHEMA",
            this.#context.encoder.deleteSchema(this.#project, this.#collection),
        );
    }
}
