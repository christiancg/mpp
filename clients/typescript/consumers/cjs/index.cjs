const { field, LocalDateTime, MppClient, MppError } = require("mpp-client");

const filter = field("openedAt").gte(LocalDateTime.of(2024, 1, 1, 10, 0));
if (filter.operator !== "GREATER_THAN_EQUALS" || String(filter.value) !== "2024-01-01T10:00") {
    throw new Error("the package loaded through require() exports the wrong values");
}
MppClient.connect({ hosts: [{ host: "127.0.0.1", port: 1 }], connectTimeoutMs: 2000 }).then(
    () => {
        throw new Error("connecting to a closed port should fail");
    },
    (error) => {
        if (!(error instanceof MppError)) {
            throw error;
        }
        process.stdout.write("cjs consumer ok\n");
    },
);
