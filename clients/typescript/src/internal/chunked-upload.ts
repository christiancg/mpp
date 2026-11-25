import { randomInt } from "node:crypto";
import { FileWriteMode, type FileMoved, type FileWritten } from "../files/types.js";

export const UPLOAD_CHUNK_BYTES = 4 * 1024 * 1024;

const STAGING_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
const STAGING_SUFFIX_LENGTH = 8;

export type UploadSource = AsyncIterable<Uint8Array> | ReadableStream<Uint8Array>;

export interface UploadSink {
    write(path: string, chunk: Uint8Array, mode: FileWriteMode): Promise<FileWritten>;
    move(from: string, to: string): Promise<FileMoved>;
    discard(path: string): Promise<unknown>;
}

function randomSuffix(): string {
    let suffix = "";
    for (let index = 0; index < STAGING_SUFFIX_LENGTH; index++) {
        suffix += STAGING_ALPHABET.charAt(randomInt(STAGING_ALPHABET.length));
    }
    return suffix;
}

export function stagingPath(path: string, suffix: string = randomSuffix()): string {
    const slash = path.lastIndexOf("/");
    return `${path.slice(0, slash + 1)}.${path.slice(slash + 1)}.upload-${suffix}`;
}

async function* fromReader(stream: ReadableStream<Uint8Array>): AsyncGenerator<Uint8Array> {
    const reader = stream.getReader();
    try {
        for (;;) {
            const next = await reader.read();
            if (next.done) {
                return;
            }
            yield next.value;
        }
    } finally {
        reader.releaseLock();
    }
}

function pieces(source: UploadSource): AsyncIterable<Uint8Array> {
    return "getReader" in source ? fromReader(source) : source;
}

export async function* rechunk(source: AsyncIterable<Uint8Array>, size: number): AsyncGenerator<Uint8Array> {
    let buffer = new Uint8Array(size);
    let filled = 0;
    for await (const piece of source) {
        let offset = 0;
        while (offset < piece.byteLength) {
            const taken = Math.min(size - filled, piece.byteLength - offset);
            buffer.set(piece.subarray(offset, offset + taken), filled);
            filled += taken;
            offset += taken;
            if (filled === size) {
                yield buffer;
                buffer = new Uint8Array(size);
                filled = 0;
            }
        }
    }
    if (filled > 0) {
        yield buffer.subarray(0, filled);
    }
}

async function stage(sink: UploadSink, staging: string, source: UploadSource, chunkBytes: number): Promise<number> {
    let size = 0;
    let mode: FileWriteMode = FileWriteMode.CREATE;
    for await (const chunk of rechunk(pieces(source), chunkBytes)) {
        await sink.write(staging, chunk, mode);
        mode = FileWriteMode.APPEND;
        size += chunk.byteLength;
    }
    if (mode === FileWriteMode.CREATE) {
        await sink.write(staging, new Uint8Array(0), mode);
    }
    return size;
}

export async function upload(
    sink: UploadSink,
    path: string,
    source: UploadSource,
    chunkBytes: number = UPLOAD_CHUNK_BYTES,
): Promise<FileWritten> {
    const staging = stagingPath(path);
    try {
        const size = await stage(sink, staging, source, chunkBytes);
        const placed = await sink.move(staging, path);
        return { path: placed.path, version: placed.version, size };
    } catch (failure) {
        await sink.discard(staging).catch(() => undefined);
        throw failure;
    }
}
