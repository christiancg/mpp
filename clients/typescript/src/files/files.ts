import { upload, type UploadSink } from "../internal/chunked-upload.js";
import type { ClientContext } from "../internal/client-context.js";
import {
    deleteFile,
    fileContent,
    listFiles,
    makeDirectory,
    moveFile,
    readFile,
    statFile,
    writeFile,
    type FileEncoding,
} from "../internal/file-request-encoder.js";
import {
    bytesToText,
    decodeBase64,
    directoryMade,
    entries,
    moved,
    range,
    stat,
    written,
} from "../internal/file-replies.js";
import { numberOf, textOf } from "../internal/response-decoder.js";
import { readWhole } from "../internal/windowed-read.js";
import type { JsonObject } from "../internal/values-codec.js";
import {
    FileWriteMode,
    type DirectoryMade,
    type FileDeleteOptions,
    type FileListOptions,
    type FileMoved,
    type FileMoveOptions,
    type FileRange,
    type FileStat,
    type FileWriteOptions,
    type FileWritten,
} from "./types.js";

export class Files {
    readonly #context: ClientContext;
    readonly #project: string;

    constructor(context: ClientContext, project: string) {
        this.#context = context;
        this.#project = project;
    }

    async write(path: string, data: Uint8Array | string, options: FileWriteOptions = {}): Promise<FileWritten> {
        const reply = await this.#context.channel.send(
            "WRITE_FILE",
            writeFile(this.#project, path, fileContent(data), options),
        );
        return written(reply);
    }

    append(path: string, data: Uint8Array | string): Promise<FileWritten> {
        return this.write(path, data, { mode: FileWriteMode.APPEND });
    }

    upload(path: string, source: AsyncIterable<Uint8Array> | ReadableStream<Uint8Array>): Promise<FileWritten> {
        const sink: UploadSink = {
            write: (target, chunk, mode) => this.write(target, chunk, { mode }),
            move: (from, to) => this.move(from, to, { overwrite: true }),
            discard: (target) => this.delete(target),
        };
        return upload(sink, path, source);
    }

    read(path: string): Promise<Uint8Array> {
        return readWhole(path, (offset, length) => this.readRange(path, offset, length));
    }

    async readText(path: string): Promise<string> {
        return bytesToText(await this.read(path));
    }

    async readRange(path: string, offset: number, length: number): Promise<FileRange<Uint8Array>> {
        const reply = await this.#read(path, offset, length, "base64");
        return range(reply, decodeBase64(textOf(reply.content) ?? ""));
    }

    async readTextRange(path: string, offset: number, length: number): Promise<FileRange<string>> {
        const reply = await this.#read(path, offset, length, "utf8");
        return range(reply, textOf(reply.content) ?? "");
    }

    async stat(path: string): Promise<FileStat> {
        return stat(await this.#context.channel.send("STAT_FILE", statFile(this.#project, path)));
    }

    async *list(path = "/", options: FileListOptions = {}): AsyncGenerator<FileStat, void, undefined> {
        let after = options.after;
        for (;;) {
            const reply = await this.#context.channel.send(
                "LIST_FILES",
                listFiles(this.#project, path, options, after),
            );
            yield* entries(reply);
            after = textOf(reply.next);
            if (after === undefined) {
                return;
            }
        }
    }

    async delete(path: string, options: FileDeleteOptions = {}): Promise<number> {
        const reply = await this.#context.channel.send("DELETE_FILE", deleteFile(this.#project, path, options));
        return numberOf(reply.deleted) ?? 0;
    }

    async move(from: string, to: string, options: FileMoveOptions = {}): Promise<FileMoved> {
        return moved(await this.#context.channel.send("MOVE_FILE", moveFile(this.#project, from, to, options)));
    }

    async makeDirectory(path: string): Promise<DirectoryMade> {
        return directoryMade(await this.#context.channel.send("MAKE_DIRECTORY", makeDirectory(this.#project, path)));
    }

    #read(path: string, offset: number, length: number, encoding: FileEncoding): Promise<JsonObject> {
        return this.#context.channel.send("READ_FILE", readFile(this.#project, path, offset, length, encoding));
    }
}
