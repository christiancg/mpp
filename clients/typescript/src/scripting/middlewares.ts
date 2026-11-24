import type { ClientContext } from "../internal/client-context.js";
import { middleware } from "../internal/descriptors.js";
import { documents, numberOf } from "../internal/response-decoder.js";
import type { MiddlewareDefinition, MiddlewareInfo, MiddlewareSaved } from "./definitions.js";

export class Middlewares {
    readonly #context: ClientContext;
    readonly #project: string;

    constructor(context: ClientContext, project: string) {
        this.#context = context;
        this.#project = project;
    }

    async save(definition: MiddlewareDefinition): Promise<MiddlewareSaved> {
        const reply = await this.#context.channel.send(
            "SAVE_MIDDLEWARE",
            this.#context.scripts.saveMiddleware(this.#project, definition),
        );
        return { version: numberOf(reply.version) ?? 0, procedureVersion: numberOf(reply.procedureVersion) ?? 0 };
    }

    async delete(name: string): Promise<void> {
        await this.#context.channel.send(
            "DELETE_MIDDLEWARE",
            this.#context.scripts.deleteMiddleware(this.#project, name),
        );
    }

    async list(): Promise<MiddlewareInfo[]> {
        const reply = await this.#context.channel.send(
            "LIST_MIDDLEWARES",
            this.#context.scripts.listMiddlewares(this.#project),
        );
        return documents(reply, "middlewares").map(middleware);
    }
}
