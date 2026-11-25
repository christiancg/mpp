package io.github.christiancg.mpp.client.files;

import java.util.Objects;
import java.util.Optional;

public record WriteOptions(WriteMode mode, boolean createParents, Optional<String> ifVersion) {
    public static final String ABSENT = "0";

    public WriteOptions {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(ifVersion, "ifVersion");
    }

    public static WriteOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private WriteMode mode = WriteMode.REPLACE;
        private boolean createParents = true;
        private String ifVersion;

        private Builder() {
        }

        public Builder mode(WriteMode mode) {
            this.mode = Objects.requireNonNull(mode, "mode");
            return this;
        }

        public Builder createParents(boolean createParents) {
            this.createParents = createParents;
            return this;
        }

        public Builder ifVersion(String version) {
            this.ifVersion = Objects.requireNonNull(version, "version");
            return this;
        }

        public Builder ifAbsent() {
            return ifVersion(ABSENT);
        }

        public WriteOptions build() {
            return new WriteOptions(mode, createParents, Optional.ofNullable(ifVersion));
        }
    }
}
