package io.github.christiancg.mpp.client.scripting;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

public final class MiddlewareDefinition {
    private final String name;
    private final MiddlewarePhase phase;
    private final Integer order;
    private final MiddlewareMatch match;
    private final String procedureName;
    private final ProcedureDefinition procedure;
    private final Duration timeout;
    private final String description;
    private final Boolean enabled;
    private final Long ifVersion;

    private MiddlewareDefinition(Builder builder) {
        if ((builder.procedureName == null) == (builder.procedure == null)) {
            throw new IllegalArgumentException("a middleware takes exactly one of procedureName and procedure");
        }
        if (builder.procedure != null && builder.procedure.ifVersion().isPresent()) {
            throw new IllegalArgumentException("an inline procedure takes no ifVersion");
        }
        name = builder.name;
        phase = builder.phase;
        order = builder.order;
        match = builder.match;
        procedureName = builder.procedureName;
        procedure = builder.procedure;
        timeout = builder.timeout;
        description = builder.description;
        enabled = builder.enabled;
        ifVersion = builder.ifVersion;
    }

    public static Builder builder(String name, MiddlewarePhase phase) {
        return new Builder(name, phase);
    }

    public String name() {
        return name;
    }

    public MiddlewarePhase phase() {
        return phase;
    }

    public OptionalInt order() {
        return order == null ? OptionalInt.empty() : OptionalInt.of(order);
    }

    public Optional<MiddlewareMatch> match() {
        return Optional.ofNullable(match);
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
        private final MiddlewarePhase phase;
        private Integer order;
        private MiddlewareMatch match;
        private String procedureName;
        private ProcedureDefinition procedure;
        private Duration timeout;
        private String description;
        private Boolean enabled;
        private Long ifVersion;

        private Builder(String name, MiddlewarePhase phase) {
            this.name = Objects.requireNonNull(name, "name");
            this.phase = Objects.requireNonNull(phase, "phase");
        }

        public Builder order(int order) {
            this.order = order;
            return this;
        }

        public Builder match(MiddlewareMatch match) {
            this.match = Objects.requireNonNull(match, "match");
            return this;
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

        public MiddlewareDefinition build() {
            return new MiddlewareDefinition(this);
        }
    }
}
