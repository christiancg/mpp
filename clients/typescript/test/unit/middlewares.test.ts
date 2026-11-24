import { describe, expect, test } from "vitest";
import { EntrypointMethod, MiddlewarePhase, Project } from "../../src/index.js";
import type { JsonObject } from "../../src/internal/values-codec.js";
import { RecordingChannel, recordingContext } from "../support/recording-channel.js";

function setup(reply: (request: JsonObject) => JsonObject) {
    const channel = new RecordingChannel();
    channel.replies = (request) => ({ type: request.type ?? null, status: "OK", ...reply(request) });
    return { channel, shop: new Project(recordingContext(channel), "shop") };
}

const byName = { name: "auth", phase: MiddlewarePhase.INGRESS, procedureName: "checkToken" } as const;

describe("middleware replies", () => {
    test("middlewares decode saves and lists", async () => {
        const match = {
            methods: ["GET", "POST"],
            pathPrefix: "/orders",
            entrypoints: ["getOrder"],
            exclude: [{ entrypoints: ["listOrders"] }, "ignored", { methods: ["DELETE"], pathPrefix: "/orders/x" }],
        };
        const full = {
            name: "auth",
            phase: "INGRESS",
            order: 10,
            match,
            procedureName: "checkToken",
            timeoutMs: 2000,
            enabled: false,
            version: 3,
            definer: "alice",
            description: "Bearer-token filter",
            disabledReason: "definer deleted",
            createdAt: 1763700000000,
        };
        const plain = { name: "log", match: { pathPrefix: "/orders" } };
        const sparse = { name: "cors" };
        const { shop, channel } = setup((request) =>
            request.type === "LIST_MIDDLEWARES"
                ? { middlewares: [full, plain, sparse] }
                : request.type === "SAVE_MIDDLEWARE"
                  ? { version: 2, procedureVersion: 5 }
                  : {},
        );
        expect(await shop.middlewares().save(byName)).toEqual({ version: 2, procedureVersion: 5 });
        const [decoded, partial, empty] = await shop.middlewares().list();
        expect(decoded).toEqual({
            name: "auth",
            phase: "INGRESS",
            order: 10,
            match: {
                methods: ["GET", "POST"],
                pathPrefix: "/orders",
                entrypoints: ["getOrder"],
                exclude: [{ entrypoints: ["listOrders"] }, { methods: ["DELETE"], pathPrefix: "/orders/x" }],
            },
            procedureName: "checkToken",
            timeoutMs: 2000,
            enabled: false,
            version: 3,
            definer: "alice",
            description: "Bearer-token filter",
            disabledReason: "definer deleted",
            raw: full,
        });
        expect(partial?.match).toEqual({ pathPrefix: "/orders" });
        expect(empty).toEqual({
            name: "cors",
            phase: "",
            order: 0,
            match: undefined,
            procedureName: "",
            timeoutMs: 0,
            enabled: true,
            version: 0,
            definer: undefined,
            description: undefined,
            disabledReason: undefined,
            raw: sparse,
        });
        await shop.middlewares().delete("auth");
        expect(channel.requests.at(-1)).toEqual({ type: "DELETE_MIDDLEWARE", projectName: "shop", name: "auth" });
    });

    test("a save sends only the fields given", async () => {
        const { shop, channel } = setup(() => ({}));
        expect(await shop.middlewares().save(byName)).toEqual({ version: 0, procedureVersion: 0 });
        expect(channel.requests.at(-1)).toEqual({
            type: "SAVE_MIDDLEWARE",
            projectName: "shop",
            name: "auth",
            phase: "INGRESS",
            procedureName: "checkToken",
        });
        await shop.middlewares().save({ ...byName, match: { methods: [EntrypointMethod.GET] } });
        expect(channel.requests.at(-1)?.match).toEqual({ methods: ["GET"] });
    });

    test("a middleware needs exactly one of procedureName and procedure", async () => {
        const { shop, channel } = setup(() => ({}));
        const procedure = { name: "checkToken", script: "return 0;" };
        await expect(shop.middlewares().save({ name: "auth", phase: MiddlewarePhase.EGRESS })).rejects.toThrow(
            /a middleware takes exactly one of procedureName and procedure/,
        );
        await expect(shop.middlewares().save({ ...byName, procedure })).rejects.toThrow(
            /exactly one of procedureName and procedure/,
        );
        await expect(
            shop.middlewares().save({
                name: "cors",
                phase: MiddlewarePhase.EGRESS,
                procedure: { ...procedure, ifVersion: 1 },
            }),
        ).rejects.toThrow(/inline procedure takes no ifVersion/);
        expect(channel.requests).toEqual([]);
    });
});
