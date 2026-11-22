import type { ClientContext } from "../internal/client-context.js";
import { entrypoint } from "../internal/descriptors.js";
import { documents, numberOf } from "../internal/response-decoder.js";
import type { EntrypointDefinition, EntrypointInfo, EntrypointSaved } from "./definitions.js";

export class Entrypoints {
    readonly #context: ClientContext;
    readonly #project: string;

    constructor(context: ClientContext, project: string) {
        this.#context = context;
        this.#project = project;
    }

    async save(definition: EntrypointDefinition): Promise<EntrypointSaved> {
        const reply = await this.#context.channel.send(
            "SAVE_ENTRYPOINT",
            this.#context.scripts.saveEntrypoint(this.#project, definition),
        );
        return { version: numberOf(reply.version) ?? 0, procedureVersion: numberOf(reply.procedureVersion) ?? 0 };
    }

    async delete(name: string): Promise<void> {
        await this.#context.channel.send(
            "DELETE_ENTRYPOINT",
            this.#context.scripts.deleteEntrypoint(this.#project, name),
        );
    }

    async list(): Promise<EntrypointInfo[]> {
        const reply = await this.#context.channel.send(
            "LIST_ENTRYPOINTS",
            this.#context.scripts.listEntrypoints(this.#project),
        );
        return documents(reply, "entrypoints").map(entrypoint);
    }
}
