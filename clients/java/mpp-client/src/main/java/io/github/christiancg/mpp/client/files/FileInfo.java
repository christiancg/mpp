package io.github.christiancg.mpp.client.files;

import java.time.Instant;
import java.util.Objects;

public record FileInfo(String path, FileKind kind, long size, String version, Instant modifiedAt) {
    public FileInfo {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(modifiedAt, "modifiedAt");
    }
}
