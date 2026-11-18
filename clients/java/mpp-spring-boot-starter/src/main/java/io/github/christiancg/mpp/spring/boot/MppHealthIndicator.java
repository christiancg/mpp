package io.github.christiancg.mpp.spring.boot;

import io.github.christiancg.mpp.client.MppClient;
import org.jspecify.annotations.NullMarked;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;

@NullMarked
public class MppHealthIndicator extends AbstractHealthIndicator {
    private static final String READY = "READY";
    private static final String STATE = "state";

    private final MppClient client;

    public MppHealthIndicator(MppClient client) {
        super("MPP health check failed");
        this.client = client;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        final var info = client.serverInfo();
        final var state = info.state().orElse("UNKNOWN");
        if (READY.equals(state)) {
            builder.up();
        } else {
            builder.outOfService();
        }
        builder.withDetail(STATE, state);
        info.version().ifPresent(version -> builder.withDetail("version", version));
    }
}
