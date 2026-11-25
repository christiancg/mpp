import {
    FileKind,
    type DirectoryMade,
    type FileMoved,
    type FileRange,
    type FileStat,
    type FileWritten,
} from "../files/types.js";
import { booleanOf, numberOf, textOf } from "./response-decoder.js";
import type { Json, JsonObject } from "./values-codec.js";

function text(frame: JsonObject, field: string): string {
    return textOf(frame[field]) ?? "";
}

function whole(frame: JsonObject, field: string): number {
    return numberOf(frame[field]) ?? 0;
}

export function decodeBase64(content: string): Uint8Array {
    const decoded = Buffer.from(content, "base64");
    const bytes = new Uint8Array(decoded.byteLength);
    bytes.set(decoded);
    return bytes;
}

export function bytesToText(bytes: Uint8Array): string {
    return Buffer.from(bytes.buffer, bytes.byteOffset, bytes.byteLength).toString("utf8");
}

export function written(frame: JsonObject): FileWritten {
    return { path: text(frame, "path"), version: text(frame, "version"), size: whole(frame, "size") };
}

export function range<C>(frame: JsonObject, content: C): FileRange<C> {
    return {
        path: text(frame, "path"),
        version: text(frame, "version"),
        size: whole(frame, "size"),
        offset: whole(frame, "offset"),
        content,
        eof: booleanOf(frame.eof, true),
    };
}

export function stat(frame: JsonObject): FileStat {
    return {
        path: text(frame, "path"),
        kind: frame.kind === FileKind.DIRECTORY ? FileKind.DIRECTORY : FileKind.FILE,
        size: whole(frame, "size"),
        version: text(frame, "version"),
        modifiedAt: whole(frame, "modifiedAt"),
    };
}

function isObject(node: Json): node is JsonObject {
    return typeof node === "object" && node !== null && !Array.isArray(node);
}

export function entries(frame: JsonObject): FileStat[] {
    const node = frame.entries;
    return Array.isArray(node) ? node.filter(isObject).map(stat) : [];
}

export function moved(frame: JsonObject): FileMoved {
    return { path: text(frame, "path"), version: text(frame, "version"), moved: whole(frame, "moved") };
}

export function directoryMade(frame: JsonObject): DirectoryMade {
    return { path: text(frame, "path"), version: text(frame, "version"), created: whole(frame, "created") };
}
