import { beforeAll, describe, expect, test } from "vitest";
import { ConflictError, FileKind, FileWriteMode, NotFoundError, type Files } from "../../src/index.js";
import { serverIt } from "./server-it.js";

const it = serverIt();
const MB = 1024 * 1024;

let files: Files;

async function collect<T>(entries: AsyncIterable<T>): Promise<T[]> {
    const seen: T[] = [];
    for await (const entry of entries) {
        seen.push(entry);
    }
    return seen;
}

async function* slices(data: Uint8Array, size: number): AsyncGenerator<Uint8Array> {
    for (let offset = 0; offset < data.byteLength; offset += size) {
        await Promise.resolve();
        yield data.subarray(offset, offset + size);
    }
}

beforeAll(async () => {
    const project = it.client.project("files_it");
    await project.create();
    files = project.files();
});

describe("project files", () => {
    test("binary and text round trips, appends and stats", async () => {
        const bytes = Uint8Array.from({ length: 256 }, (_, index) => index);
        const written = await files.write("/bin/all.bin", bytes);
        expect(written).toMatchObject({ path: "/bin/all.bin", size: 256 });
        expect(await files.read("/bin/all.bin")).toEqual(bytes);
        expect((await files.readRange("/bin/all.bin", 250, 100)).content).toEqual(bytes.subarray(250));
        const pastEnd = await files.readRange("/bin/all.bin", 1000, 10);
        expect(pastEnd).toMatchObject({ eof: true, size: 256 });
        expect(pastEnd.content).toEqual(new Uint8Array(0));

        await files.write("/text/hello.txt", "héllo, wörld €");
        expect(await files.readText("/text/hello.txt")).toBe("héllo, wörld €");
        await files.append("/text/log.txt", "one\n");
        const appended = await files.append("/text/log.txt", "two\n");
        expect(appended.size).toBe(8);
        expect(await files.readText("/text/log.txt")).toBe("one\ntwo\n");
        expect((await files.readTextRange("/text/log.txt", 4, 3)).content).toBe("two");

        const stat = await files.stat("/text/log.txt");
        expect(stat).toMatchObject({ path: "/text/log.txt", kind: FileKind.FILE, size: 8, version: appended.version });
        expect(stat.modifiedAt).toBeGreaterThan(0);
        expect((await files.stat("/text")).kind).toBe(FileKind.DIRECTORY);

        await expect(files.write("/text/log.txt", "x", { mode: FileWriteMode.CREATE })).rejects.toMatchObject({
            rawCode: "409-20",
        });
        await expect(files.write("/text/log.txt", "x", { ifVersion: "1" })).rejects.toBeInstanceOf(ConflictError);
        const replaced = await files.write("/text/log.txt", "x", { ifVersion: appended.version });
        expect(replaced.size).toBe(1);
    });

    test("a listing pages through its entries", async () => {
        await files.makeDirectory("/list/deep/er");
        for (const name of ["a", "b", "c", "d", "e"]) {
            await files.write(`/list/${name}.txt`, name);
        }
        const flat = await collect(files.list("/list", { pageSize: 2 }));
        expect(flat.map((entry) => entry.path)).toEqual([
            "/list/a.txt",
            "/list/b.txt",
            "/list/c.txt",
            "/list/d.txt",
            "/list/deep",
            "/list/e.txt",
        ]);
        const deep = await collect(files.list("/list", { recursive: true, pageSize: 3 }));
        expect(deep.map((entry) => entry.path)).toContain("/list/deep/er");
        expect(deep.find((entry) => entry.path === "/list/deep/er")?.kind).toBe(FileKind.DIRECTORY);
        const after = await collect(files.list("/list", { after: "/list/c.txt" }));
        expect(after.map((entry) => entry.path)).toEqual(["/list/d.txt", "/list/deep", "/list/e.txt"]);
    });

    test("moves and recursive deletes", async () => {
        await files.write("/move/src/one.txt", "1");
        await files.write("/move/src/two/three.txt", "3");
        const moved = await files.move("/move/src", "/move/dst");
        expect(moved.path).toBe("/move/dst");
        expect(moved.moved).toBeGreaterThan(0);
        expect(await files.readText("/move/dst/two/three.txt")).toBe("3");
        await expect(files.stat("/move/src")).rejects.toBeInstanceOf(NotFoundError);

        await files.write("/move/other.txt", "o");
        await expect(files.move("/move/other.txt", "/move/dst/one.txt")).rejects.toMatchObject({ rawCode: "409-20" });
        await files.move("/move/other.txt", "/move/dst/one.txt", { overwrite: true });
        expect(await files.readText("/move/dst/one.txt")).toBe("o");

        await expect(files.delete("/move/dst")).rejects.toMatchObject({ rawCode: "409-22" });
        expect(await files.delete("/move/dst", { recursive: true })).toBeGreaterThanOrEqual(4);
        expect(await files.delete("/move/dst")).toBe(0);
        const missing = files.stat("/move/dst/one.txt");
        await expect(missing).rejects.toBeInstanceOf(NotFoundError);
        await expect(missing).rejects.toMatchObject({ rawCode: "404-14" });
    });

    test("a chunked upload of a 9 MB file reads back byte for byte", async () => {
        const data = new Uint8Array(9 * MB + 12345);
        for (let index = 0; index < data.byteLength; index++) {
            data[index] = (index * 31 + (index >> 11)) & 0xff;
        }
        await files.write("/big/blob.bin", "old content");
        const uploaded = await files.upload("/big/blob.bin", slices(data, 700_000));
        expect(uploaded).toMatchObject({ path: "/big/blob.bin", size: data.byteLength });
        expect((await files.stat("/big/blob.bin")).size).toBe(data.byteLength);
        const read = await files.read("/big/blob.bin");
        expect(read.byteLength).toBe(data.byteLength);
        expect(Buffer.from(read).equals(Buffer.from(data))).toBe(true);
        const names = (await collect(files.list("/big"))).map((entry) => entry.path);
        expect(names).toEqual(["/big/blob.bin"]);
    });

    test("a failed upload leaves no staging file behind", async () => {
        await files.makeDirectory("/clash/target");
        await files.write("/clash/target/inside.txt", "x");
        await expect(files.upload("/clash/target", slices(Uint8Array.of(1, 2, 3), 2))).rejects.toBeInstanceOf(
            ConflictError,
        );
        const names = (await collect(files.list("/clash"))).map((entry) => entry.path);
        expect(names).toEqual(["/clash/target"]);
    });
});
