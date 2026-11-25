import { FileWriteMode } from "../../src/index.js";
import type { FixtureCall } from "./recorder.js";

const UTF8 = new TextEncoder();

async function drain(entries: AsyncIterable<unknown>): Promise<unknown[]> {
    const seen: unknown[] = [];
    for await (const entry of entries) {
        seen.push(entry);
    }
    return seen;
}

export function addFileCases(cases: Map<string, FixtureCall>): void {
    cases.set("files.write_file", (r) => r.shop.files().write("/public/index.html", UTF8.encode("<h1>hi</h1>")));
    cases.set("files.write_file_text", (r) => r.shop.files().append("/logs/app.log", "started\n"));
    cases.set("files.write_file_conditional", (r) =>
        r.shop.files().write("/config.json", UTF8.encode("{}"), {
            mode: FileWriteMode.CREATE,
            createParents: false,
            ifVersion: "0",
        }),
    );
    cases.set("files.read_file", (r) => r.shop.files().readRange("/public/index.html", 0, 1048576));
    cases.set("files.read_file_text", (r) => r.shop.files().readTextRange("/logs/app.log", 8, 4096));
    cases.set("files.stat_file", (r) => r.shop.files().stat("/public/index.html"));
    cases.set("files.list_files", (r) =>
        drain(r.shop.files().list("/public", { recursive: true, pageSize: 1000, after: "/public/app.js" })),
    );
    cases.set("files.delete_file", (r) => r.shop.files().delete("/public", { recursive: true }));
    cases.set("files.move_file", (r) => r.shop.files().move("/public", "/www"));
    cases.set("files.make_directory", (r) => r.shop.files().makeDirectory("/assets/img"));
}
