package io.github.christiancg.mpp.client.files;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

public record ListOptions(boolean recursive, OptionalInt pageSize, Optional<String> startAfter) {
    public static final int MAX_PAGE_SIZE = 10_000;

    public ListOptions {
        Objects.requireNonNull(pageSize, "pageSize");
        Objects.requireNonNull(startAfter, "startAfter");
        if (pageSize.isPresent() && (pageSize.getAsInt() < 1 || pageSize.getAsInt() > MAX_PAGE_SIZE)) {
            throw new IllegalArgumentException(
                    "pageSize must be between 1 and " + MAX_PAGE_SIZE + ": " + pageSize.getAsInt());
        }
    }

    public static ListOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean recursive;
        private Integer pageSize;
        private String startAfter;

        private Builder() {
        }

        public Builder recursive(boolean recursive) {
            this.recursive = recursive;
            return this;
        }

        public Builder pageSize(int pageSize) {
            this.pageSize = pageSize;
            return this;
        }

        public Builder startAfter(String path) {
            this.startAfter = Objects.requireNonNull(path, "path");
            return this;
        }

        public ListOptions build() {
            return new ListOptions(recursive, pageSize == null ? OptionalInt.empty() : OptionalInt.of(pageSize),
                    Optional.ofNullable(startAfter));
        }
    }
}
