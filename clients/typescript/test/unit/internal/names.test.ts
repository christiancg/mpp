import { describe, expect, test } from "vitest";
import { collectionName, projectName, documentId } from "../../../src/internal/names.js";

describe("names", () => {
    test("accepts names of 3-64 characters from the allowed set", () => {
        expect(projectName("shop")).toBe("shop");
        expect(collectionName("a_b-C9")).toBe("a_b-C9");
        expect(projectName("x".repeat(64))).toHaveLength(64);
    });

    test("rejects names that are short, long, or hold other characters", () => {
        expect(() => projectName("ab")).toThrow(/invalid project name 'ab'/);
        expect(() => collectionName("x".repeat(65))).toThrow(/invalid collection name/);
        expect(() => collectionName("a.b.c")).toThrow(TypeError);
        expect(() => projectName(undefined)).toThrow(/'undefined'/);
    });

    test("ids take 1-64 characters", () => {
        expect(documentId("a")).toBe("a");
        expect(() => documentId("")).toThrow(/invalid _id/);
        expect(() => documentId("a b")).toThrow(TypeError);
        expect(() => documentId(5)).toThrow(TypeError);
    });
});
