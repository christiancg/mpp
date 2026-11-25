import { FileChangedError } from "../errors/errors.js";
import type { FileRange } from "../files/types.js";

export const READ_WINDOW_BYTES = 4 * 1024 * 1024;
export const READ_RESTARTS = 3;

export type WindowReader = (offset: number, length: number) => Promise<FileRange<Uint8Array>>;

function concat(parts: readonly Uint8Array[], total: number): Uint8Array {
    const whole = new Uint8Array(total);
    let offset = 0;
    for (const part of parts) {
        whole.set(part, offset);
        offset += part.byteLength;
    }
    return whole;
}

async function readOnce(reader: WindowReader, windowBytes: number): Promise<Uint8Array | undefined> {
    const parts: Uint8Array[] = [];
    let version: string | undefined;
    let offset = 0;
    for (;;) {
        const window = await reader(offset, windowBytes);
        if (version !== undefined && window.version !== version) {
            return undefined;
        }
        version = window.version;
        parts.push(window.content);
        offset += window.content.byteLength;
        if (window.eof || window.content.byteLength === 0) {
            return concat(parts, offset);
        }
    }
}

export async function readWhole(
    path: string,
    reader: WindowReader,
    windowBytes: number = READ_WINDOW_BYTES,
): Promise<Uint8Array> {
    for (let attempt = 0; attempt <= READ_RESTARTS; attempt++) {
        const whole = await readOnce(reader, windowBytes);
        if (whole !== undefined) {
            return whole;
        }
    }
    throw new FileChangedError(`${path} changed while it was read, ${READ_RESTARTS + 1} times in a row`, {
        operation: "READ_FILE",
    });
}
