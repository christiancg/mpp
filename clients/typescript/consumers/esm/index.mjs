import { and, field, GeoPoint, MppClient, MppError, MapOps, MidOps, Order, Vector } from "mpp-client";

const filter = and(field("status").eq("active"), field("location").eq(GeoPoint.of(40.71, -74)));
const operation = MapOps.set("total", MidOps.multiply("price", "qty"));
if (
    filter.operands.length !== 2 ||
    operation.field !== "total" ||
    Order.DESC !== "DESC" ||
    Vector.of(1).dimensions !== 1
) {
    throw new Error("the ESM build exports the wrong values");
}
const refusal = await MppClient.connect({ hosts: [{ host: "127.0.0.1", port: 1 }], connectTimeoutMs: 2000 }).then(
    () => undefined,
    (error) => error,
);
if (!(refusal instanceof MppError)) {
    throw new Error(`connecting to a closed port should fail with an MppError, got ${refusal}`);
}
process.stdout.write("esm consumer ok\n");
