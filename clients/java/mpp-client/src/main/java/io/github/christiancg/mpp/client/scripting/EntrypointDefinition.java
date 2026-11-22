package io.github.christiancg.mpp.client.scripting;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

public final class EntrypointDefinition {
    private final String name;
    private final EntrypointMethod method;
    private final String path;
    private final String procedureName;
    private final ProcedureDefinition procedure;
    private final Duration timeout;
    private final String description;
    private final Boolean enabled;
    private final Long ifVersion;

    private EntrypointDefinition(Builder builder) {
        if ((builder.procedureName == null) == (builder.procedure == null)) {
            throw new IllegalArgumentException("an entrypoint takes exactly one of procedureName and procedure");
        }
        if (builder.procedure != null && builder.procedure.ifVersion().isPresent()) {
            throw new IllegalArgumentException("an inline procedure takes no ifVersion");
        }
        name = builder.name;
        method = builder.method;
        path = builder.path;
        procedureName = builder.procedureName;
        procedure = builder.procedure;
        timeout = builder.timeout;
        description = builder.description;
        enabled = builder.enabled;
        ifVersion = builder.ifVersion;
    }

    public static Builder builder(String name, EntrypointMethod method, String path) {
        return new Builder(name, method, path);
    }

    public String name() {
        return name;
    }

    public EntrypointMethod method() {
        return method;
    }

    public String path() {
        return path;
    }

    public Optional<String> procedureName() {
        return Optional.ofNullable(procedureName);
    }

    public Optional<ProcedureDefinition> procedure() {
        return Optional.ofNullable(procedure);
    }

    public Optional<Duration> timeout() {
        return Optional.ofNullable(timeout);
    }

    public Optional<String> description() {
        return Optional.ofNullable(description);
    }

    public Optional<Boolean> enabled() {
        return Optional.ofNullable(enabled);
    }

    public OptionalLong ifVersion() {
        return ifVersion == null ? OptionalLong.empty() : OptionalLong.of(ifVersion);
    }

    public static final class Builder {
        private final String name;
        private final EntrypointMethod method;
        private final String path;
        private String procedureName;
        private ProcedureDefinition procedure;
        private Duration timeout;
        private String description;
        private Boolean enabled;
        private Long ifVersion;

        private Builder(String name, EntrypointMethod method, String path) {
            this.name = Objects.requireNonNull(name, "name");
            this.method = Objects.requireNonNull(method, "method");
            this.path = Objects.requireNonNull(path, "path");
        }

        public Builder procedureName(String procedureName) {
            this.procedureName = Objects.requireNonNull(procedureName, "procedureName");
            return this;
        }

        public Builder procedure(ProcedureDefinition procedure) {
            this.procedure = Objects.requireNonNull(procedure, "procedure");
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        public Builder description(String description) {
            this.description = Objects.requireNonNull(description, "description");
            return this;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder ifVersion(long version) {
            this.ifVersion = version;
            return this;
        }

        public EntrypointDefinition build() {
            return new EntrypointDefinition(this);
        }
    }
}
