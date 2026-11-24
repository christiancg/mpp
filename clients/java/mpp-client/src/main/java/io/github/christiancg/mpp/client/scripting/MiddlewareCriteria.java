package io.github.christiancg.mpp.client.scripting;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record MiddlewareCriteria(List<EntrypointMethod> methods, Optional<String> pathPrefix,
        List<String> entrypoints) {
    public MiddlewareCriteria {
        methods = List.copyOf(methods);
        Objects.requireNonNull(pathPrefix, "pathPrefix");
        entrypoints = List.copyOf(entrypoints);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private List<EntrypointMethod> methods = List.of();
        private String pathPrefix;
        private List<String> entrypoints = List.of();

        private Builder() {
        }

        public Builder methods(EntrypointMethod... methods) {
            this.methods = List.of(methods);
            return this;
        }

        public Builder pathPrefix(String pathPrefix) {
            this.pathPrefix = Objects.requireNonNull(pathPrefix, "pathPrefix");
            return this;
        }

        public Builder entrypoints(String... entrypoints) {
            this.entrypoints = List.of(entrypoints);
            return this;
        }

        public MiddlewareCriteria build() {
            return new MiddlewareCriteria(methods, Optional.ofNullable(pathPrefix), entrypoints);
        }
    }
}
