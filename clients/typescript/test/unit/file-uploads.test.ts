import { describe, expect, test } from "vitest";
import { ConflictError, Project } from "../../src/index.js";
import { rechunk, stagingPath, upload, type UploadSink } from "../../src/internal/chunked-upload.js";
import type { JsonObject } from "../../src/internal/values-codec.js";
import { error, RecordingChannel, recordingContext } from "../support/recording-channel.js";

const STAGING = /^\/docs\/\.report\.pdf\.upload-[A-Za-z0-9]{8}$/;
const MB = 1024 * 1024;

function setup(reply: (request: JsonObject) => JsonObject) {
    const channel = new RecordingChannel();
    channel.replies = reply;
    return { channel, files: new Project(recordingContext(channel), "shop").files() };
}

function ok(request: JsonObject, extra: JsonObject = {}): JsonObject {
    return { type: request.type ?? null, status: "OK", ...extra };
}

async function* bytesOf(...pieces: Uint8Array[]): AsyncGenerator<Uint8Array> {
    for (const piece of pieces) {
        await Promise.resolve();
        yield piece;
    }
}

function piecesOf(...pieces: number[][]): AsyncGenerator<Uint8Array> {
    return bytesOf(...pieces.map((piece) => Uint8Array.from(piece)));
}

async function collect(chunks: AsyncIterable<Uint8Array>): Promise<number[][]> {
    const seen: number[][] = [];
    for await (const chunk of chunks) {
        seen.push([...chunk]);
    }
    return seen;
}

function recordingSink(failMove = false, failDiscard = false) {
    const calls: string[] = [];
    const sink: UploadSink = {
        write: (path, chunk, mode) => {
            calls.push(`${mode} ${[...chunk].join(",")}`);
            return Promise.resolve({ path, version: "1", size: chunk.byteLength });
        },
        move: (_, to) => {
            calls.push(`MOVE ${to}`);
            return failMove
                ? Promise.reject(new Error("move refused"))
                : Promise.resolve({ path: to, version: "9", moved: 1 });
        },
        discard: (path) => {
            calls.push(`DISCARD ${STAGING.test(path)}`);
            return failDiscard ? Promise.reject(new Error("gone")) : Promise.resolve(1);
        },
    };
    return { calls, sink };
}

describe("chunked uploads", () => {
    test("the staging file is a hidden sibling with a random suffix", () => {
        expect(stagingPath("/docs/report.pdf")).toMatch(STAGING);
        expect(stagingPath("/docs/report.pdf", "abcd1234")).toBe("/docs/.report.pdf.upload-abcd1234");
        expect(stagingPath("report.pdf", "abcd1234")).toBe(".report.pdf.upload-abcd1234");
        expect(stagingPath("/docs/report.pdf")).not.toBe(stagingPath("/docs/report.pdf"));
    });

    test("rechunk cuts pieces into fixed chunks across piece boundaries", async () => {
        expect(await collect(rechunk(piecesOf([1, 2], [3, 4, 5, 6, 7], [], [8]), 3))).toEqual([
            [1, 2, 3],
            [4, 5, 6],
            [7, 8],
        ]);
        expect(await collect(rechunk(piecesOf([1, 2, 3]), 3))).toEqual([[1, 2, 3]]);
        expect(await collect(rechunk(piecesOf(), 3))).toEqual([]);
    });

    test("the first chunk creates the staging file, the rest append, and a move places it", async () => {
        const { calls, sink } = recordingSink();
        const placed = await upload(sink, "/docs/report.pdf", piecesOf([1, 2], [3, 4, 5]), 2);
        expect(placed).toEqual({ path: "/docs/report.pdf", version: "9", size: 5 });
        expect(calls).toEqual(["CREATE 1,2", "APPEND 3,4", "APPEND 5", "MOVE /docs/report.pdf"]);
    });

    test("an empty source still creates an empty file", async () => {
        const { calls, sink } = recordingSink();
        expect(await upload(sink, "/docs/report.pdf", piecesOf(), 2)).toMatchObject({ size: 0 });
        expect(calls).toEqual(["CREATE ", "MOVE /docs/report.pdf"]);
    });

    test("a failure discards the staging file and rethrows, even when the discard fails", async () => {
        const { calls, sink } = recordingSink(true);
        await expect(upload(sink, "/docs/report.pdf", piecesOf([1]), 2)).rejects.toThrow("move refused");
        expect(calls).toEqual(["CREATE 1", "MOVE /docs/report.pdf", "DISCARD true"]);
        const failing = recordingSink(true, true);
        await expect(upload(failing.sink, "/docs/report.pdf", piecesOf([1]), 2)).rejects.toThrow("move refused");
    });

    test("the facade writes 4 MB chunks to the sibling and moves it over the target", async () => {
        const { channel, files } = setup((request) =>
            request.type === "MOVE_FILE"
                ? ok(request, { path: request.to as string, version: "5", moved: 1 })
                : ok(request),
        );
        const data = new Uint8Array(9 * MB).map((_, index) => index % 251);
        const placed = await files.upload("/docs/report.pdf", bytesOf(data.subarray(0, 10), data.subarray(10)));
        expect(placed).toEqual({ path: "/docs/report.pdf", version: "5", size: 9 * MB });
        const writes = channel.requests.filter((request) => request.type === "WRITE_FILE");
        expect(writes.map((request) => [request.mode, request.encoding, request.createParents])).toEqual([
            ["CREATE", "base64", true],
            ["APPEND", "base64", true],
            ["APPEND", "base64", true],
        ]);
        const staging = writes[0]?.path as string;
        expect(staging).toMatch(STAGING);
        expect(writes.every((request) => request.path === staging)).toBe(true);
        const sent = Buffer.concat(writes.map((request) => Buffer.from(request.content as string, "base64")));
        expect(sent.equals(Buffer.from(data))).toBe(true);
        expect(channel.last()).toEqual({
            type: "MOVE_FILE",
            projectName: "shop",
            from: staging,
            to: "/docs/report.pdf",
            overwrite: true,
            createParents: true,
        });
    });

    test("the facade reads a ReadableStream and deletes the sibling when a write fails", async () => {
        const { channel, files } = setup((request) =>
            request.type === "WRITE_FILE" && request.mode === "APPEND"
                ? error(request, "ERROR", "409-23")
                : ok(request),
        );
        const stream = new ReadableStream<Uint8Array>({
            start(controller) {
                controller.enqueue(new Uint8Array(4 * MB));
                controller.enqueue(Uint8Array.of(1));
                controller.close();
            },
        });
        await expect(files.upload("/docs/report.pdf", stream)).rejects.toBeInstanceOf(ConflictError);
        const staging = channel.requests[0]?.path;
        expect(channel.requests.map((request) => request.type)).toEqual(["WRITE_FILE", "WRITE_FILE", "DELETE_FILE"]);
        expect(channel.last()).toEqual({ type: "DELETE_FILE", projectName: "shop", path: staging, recursive: false });
    });
});
