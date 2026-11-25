import type { FileDeleteOptions, FileListOptions, FileMoveOptions, FileWriteOptions } from "../files/types.js";
import { request } from "./request-encoder.js";
import type { JsonObject } from "./values-codec.js";

export type FileEncoding = "base64" | "utf8";

export const DEFAULT_LIST_PAGE_SIZE = 1000;

export interface FileContent {
    readonly text: string;
    readonly encoding: FileEncoding;
}

export function fileContent(data: Uint8Array | string): FileContent {
    return typeof data === "string"
        ? { text: data, encoding: "utf8" }
        : { text: Buffer.from(data.buffer, data.byteOffset, data.byteLength).toString("base64"), encoding: "base64" };
}

export function writeFile(project: string, path: string, content: FileContent, options: FileWriteOptions): JsonObject {
    return {
        ...request("WRITE_FILE", project),
        path,
        content: content.text,
        encoding: content.encoding,
        mode: options.mode ?? "REPLACE",
        createParents: options.createParents ?? true,
        ...(options.ifVersion === undefined ? {} : { ifVersion: options.ifVersion }),
    };
}

export function readFile(
    project: string,
    path: string,
    offset: number,
    length: number,
    encoding: FileEncoding,
): JsonObject {
    return { ...request("READ_FILE", project), path, offset, length, encoding };
}

export function statFile(project: string, path: string): JsonObject {
    return { ...request("STAT_FILE", project), path };
}

export function listFiles(
    project: string,
    path: string,
    options: FileListOptions,
    after: string | undefined,
): JsonObject {
    return {
        ...request("LIST_FILES", project),
        path,
        recursive: options.recursive ?? false,
        limit: options.pageSize ?? DEFAULT_LIST_PAGE_SIZE,
        ...(after === undefined ? {} : { after }),
    };
}

export function deleteFile(project: string, path: string, options: FileDeleteOptions): JsonObject {
    return { ...request("DELETE_FILE", project), path, recursive: options.recursive ?? false };
}

export function moveFile(project: string, from: string, to: string, options: FileMoveOptions): JsonObject {
    return {
        ...request("MOVE_FILE", project),
        from,
        to,
        overwrite: options.overwrite ?? false,
        createParents: options.createParents ?? true,
    };
}

export function makeDirectory(project: string, path: string): JsonObject {
    return { ...request("MAKE_DIRECTORY", project), path, recursive: true };
}
