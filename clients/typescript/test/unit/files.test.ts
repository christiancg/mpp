import { describe, expect, test } from "vitest";
import { FileChangedError, FileKind, FileWriteMode, NotFoundError, Project } from "../../src/index.js";
import type { JsonObject } from "../../src/internal/values-codec.js";
import { responses } from "../support/protocol-fixtures.js";
import { error, RecordingChannel, recordingContext } from "../support/recording-channel.js";

function setup(reply: (request: JsonObject) => JsonObject = () => ({})) {
    const channel = new RecordingChannel();
    channel.replies = (request) => ({ type: request.type ?? null, status: "OK", ...reply(request) });
    return { channel, files: new Project(recordingContext(channel), "shop").files() };
}

function base64(bytes: Uint8Array): string {
    return Buffer.from(bytes).toString("base64");
}

function windowsOf(content: Uint8Array, windowBytes: number, versionAt: (request: number) => string) {
    let served = 0;
    return (request: JsonObject): JsonObject => {
        const offset = request.offset as number;
        const window = content.subarray(offset, Math.min(offset + windowBytes, offset + (request.length as number)));
        return {
            path: request.path as string,
            version: versionAt(served++),
            size: content.byteLength,
            offset,
            content: base64(window),
            encoding: "base64",
            eof: offset + window.byteLength >= content.byteLength,
        };
    };
}

async function collect<T>(entries: AsyncIterable<T>): Promise<T[]> {
    const seen: T[] = [];
    for await (const entry of entries) {
        seen.push(entry);
    }
    return seen;
}

const BYTES = Uint8Array.from({ length: 256 }, (_, index) => index);

describe("file writes", () => {
    test("bytes go as base64 and text as utf8, with the defaults spelled out", async () => {
        const { channel, files } = setup((request) => ({ path: request.path as string, version: "7", size: 3 }));
        expect(await files.write("/a.bin", BYTES)).toEqual({ path: "/a.bin", version: "7", size: 3 });
        expect(channel.last()).toEqual({
            type: "WRITE_FILE",
            projectName: "shop",
            path: "/a.bin",
            content: base64(BYTES),
            encoding: "base64",
            mode: "REPLACE",
            createParents: true,
        });
        await files.write("/a.txt", "héllo", { mode: FileWriteMode.CREATE, createParents: false, ifVersion: "0" });
        expect(channel.last()).toMatchObject({ content: "héllo", encoding: "utf8", mode: "CREATE", ifVersion: "0" });
        await files.append("/a.txt", "more");
        expect(channel.last()).toMatchObject({ mode: "APPEND", createParents: true });
        expect(channel.last()).not.toHaveProperty("ifVersion");
    });

    test("a view into a larger buffer sends only its own bytes", async () => {
        const { channel, files } = setup();
        await files.write("/view.bin", BYTES.subarray(10, 13));
        expect(channel.last().content).toBe(base64(Uint8Array.of(10, 11, 12)));
        expect(await files.write("/empty.bin", new Uint8Array(0))).toEqual({ path: "", version: "", size: 0 });
    });
});

describe("file reads", () => {
    test("ranges decode their content and default what the reply leaves out", async () => {
        const { channel, files } = setup((request) =>
            request.encoding === "base64"
                ? { path: "/a.bin", version: "3", size: 256, offset: 4, content: base64(BYTES), eof: false }
                : { content: "héllo" },
        );
        const bytes = await files.readRange("/a.bin", 4, 256);
        expect(bytes).toEqual({ path: "/a.bin", version: "3", size: 256, offset: 4, content: BYTES, eof: false });
        expect(channel.last()).toEqual({
            type: "READ_FILE",
            projectName: "shop",
            path: "/a.bin",
            offset: 4,
            length: 256,
            encoding: "base64",
        });
        const text = await files.readTextRange("/a.txt", 0, 10);
        expect(text).toEqual({ path: "", version: "", size: 0, offset: 0, content: "héllo", eof: true });
        expect(channel.last().encoding).toBe("utf8");
        const { files: sparse } = setup(() => ({}));
        expect((await sparse.readRange("/none", 0, 1)).content).toEqual(new Uint8Array(0));
        expect((await sparse.readTextRange("/none", 0, 1)).content).toBe("");
    });

    test("a whole read pages through windows the server caps", async () => {
        const { channel, files } = setup(windowsOf(BYTES, 100, () => "1"));
        expect(await files.read("/a.bin")).toEqual(BYTES);
        expect(channel.requests.map((request) => request.offset)).toEqual([0, 100, 200]);
        expect(channel.requests.every((request) => request.length === 4 * 1024 * 1024)).toBe(true);
    });

    test("a whole read restarts from the start when the version moves between windows", async () => {
        const { channel, files } = setup(windowsOf(BYTES, 100, (served) => (served < 2 ? "1" : "2")));
        expect(await files.read("/a.bin")).toEqual(BYTES);
        expect(channel.requests.map((request) => request.offset)).toEqual([0, 100, 200, 0, 100, 200]);
    });

    test("a whole read gives up after three restarts", async () => {
        const { channel, files } = setup(windowsOf(BYTES, 100, (served) => String(served)));
        const failure = files.read("/a.bin");
        await expect(failure).rejects.toBeInstanceOf(FileChangedError);
        await expect(failure).rejects.toThrow("/a.bin changed while it was read, 4 times in a row");
        expect(channel.requests).toHaveLength(8);
    });

    test("an empty window ends the read even without eof, and text decodes as UTF-8", async () => {
        const { files } = setup(() => ({ version: "1", content: "", eof: false }));
        expect(await files.read("/empty")).toEqual(new Uint8Array(0));
        const text = new TextEncoder().encode("añb€");
        const { files: texts } = setup(windowsOf(text, 2, () => "1"));
        expect(await texts.readText("/a.txt")).toBe("añb€");
    });

    test("a missing file is a NotFoundError", async () => {
        const channel = new RecordingChannel();
        channel.replies = (request) => error(request, "NOT_FOUND", "404-14");
        const files = new Project(recordingContext(channel), "shop").files();
        await expect(files.stat("/missing")).rejects.toBeInstanceOf(NotFoundError);
    });
});

describe("file metadata", () => {
    test("stat decodes both kinds", async () => {
        const row = { path: "/d", kind: "DIRECTORY", size: 0, version: "4", modifiedAt: 1763700000000 };
        const { channel, files } = setup(() => row);
        expect(await files.stat("/d")).toEqual({ ...row, kind: FileKind.DIRECTORY });
        expect(channel.last()).toEqual({ type: "STAT_FILE", projectName: "shop", path: "/d" });
        const { files: sparse } = setup(() => ({}));
        expect(await sparse.stat("/x")).toEqual({ path: "", kind: "FILE", size: 0, version: "", modifiedAt: 0 });
    });

    test("list pages with after until next is null", async () => {
        const pages: Record<string, JsonObject> = {
            start: {
                entries: [{ path: "/a", kind: "FILE", size: 1, version: "1", modifiedAt: 5 }, "skip"],
                next: "/a",
            },
            "/a": { entries: [{ path: "/b", kind: "DIRECTORY" }], next: "/b" },
            "/b": { next: null },
        };
        const { channel, files } = setup((request) => pages[(request.after as string | undefined) ?? "start"] ?? {});
        const listed = await collect(files.list());
        expect(listed.map((entry) => [entry.path, entry.kind])).toEqual([
            ["/a", "FILE"],
            ["/b", "DIRECTORY"],
        ]);
        expect(channel.requests).toEqual([
            { type: "LIST_FILES", projectName: "shop", path: "/", recursive: false, limit: 1000 },
            { type: "LIST_FILES", projectName: "shop", path: "/", recursive: false, limit: 1000, after: "/a" },
            { type: "LIST_FILES", projectName: "shop", path: "/", recursive: false, limit: 1000, after: "/b" },
        ]);
        await collect(files.list("/docs", { recursive: true, pageSize: 2, after: "/docs/a" }));
        expect(channel.last()).toMatchObject({ path: "/docs", recursive: true, limit: 2, after: "/docs/a" });
    });

    test("delete, move and makeDirectory send their defaults and decode their replies", async () => {
        const { channel, files } = setup((request) =>
            request.type === "DELETE_FILE"
                ? { deleted: 3 }
                : request.type === "MOVE_FILE"
                  ? { path: "/b", version: "2", moved: 1 }
                  : request.type === "MAKE_DIRECTORY"
                    ? { path: "/d", version: "1", created: 2 }
                    : {},
        );
        expect(await files.delete("/a")).toBe(3);
        expect(channel.last()).toEqual({ type: "DELETE_FILE", projectName: "shop", path: "/a", recursive: false });
        expect(await files.move("/a", "/b", { overwrite: true, createParents: false })).toEqual({
            path: "/b",
            version: "2",
            moved: 1,
        });
        expect(channel.last()).toEqual({
            type: "MOVE_FILE",
            projectName: "shop",
            from: "/a",
            to: "/b",
            overwrite: true,
            createParents: false,
        });
        expect(await files.makeDirectory("/d")).toEqual({ path: "/d", version: "1", created: 2 });
        expect(channel.last()).toEqual({ type: "MAKE_DIRECTORY", projectName: "shop", path: "/d", recursive: true });
        const { files: sparse } = setup(() => ({}));
        expect(await sparse.delete("/a", { recursive: true })).toBe(0);
        expect(await sparse.move("/a", "/b")).toEqual({ path: "", version: "", moved: 0 });
        expect(await sparse.makeDirectory("/d")).toEqual({ path: "", version: "", created: 0 });
    });
});

describe("the file reply fixtures", () => {
    test("decode through the facade", async () => {
        const frames = new Map(responses().map((fixture) => [fixture.name, fixture.body.frame as JsonObject]));
        const channel = new RecordingChannel();
        channel.replies = (request) => frames.get(`ok.${(request.type as string).toLowerCase()}`) ?? {};
        const files = new Project(recordingContext(channel), "shop").files();
        const version = "117673399272407040";
        expect(await files.write("/public/index.html", "<h1>hi</h1>")).toEqual({
            path: "/public/index.html",
            version,
            size: 11,
        });
        expect(await files.readText("/public/index.html")).toBe("<h1>hi</h1>");
        expect(await files.stat("/public")).toEqual({
            path: "/public",
            kind: "DIRECTORY",
            size: 0,
            version,
            modifiedAt: 1795553577765,
        });
        const listed = files.list("/", { recursive: true });
        expect((await listed.next()).value).toMatchObject({ path: "/public", kind: "DIRECTORY" });
        expect((await listed.next()).value).toMatchObject({ path: "/public/index.html", kind: "FILE", size: 11 });
        await listed.return();
        expect(await files.delete("/public", { recursive: true })).toBe(2);
        expect(await files.move("/public", "/www")).toEqual({ path: "/www", version, moved: 2 });
        expect(await files.makeDirectory("/assets/img")).toEqual({ path: "/assets/img", version, created: 2 });
    });
});
