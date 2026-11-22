package org.ccg.mpp.unit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.ccg.mpp.config.ConfigurationValidator;
import org.ccg.mpp.test.ConfigFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class HttpConfigurationValidatorTest {

    private static List<String> entrypointErrors(Map<String, String> config) {
        return ConfigurationValidator.validate(config).stream()
                .filter(error -> error.startsWith("httpPort") || error.startsWith("entrypoint")).toList();
    }

    @Test
    public void test_the_shipped_defaults_are_valid(@TempDir Path tempDir) {
        final var config = ConfigFixture.fullValid(tempDir);
        config.remove("httpPort");
        config.remove("entrypointTimeoutMs");
        config.remove("entrypointMaxTimeoutMs");

        assertEquals(List.of(), entrypointErrors(config));
    }

    @Test
    public void test_http_port_equal_to_port_is_refused(@TempDir Path tempDir) {
        final var config = ConfigFixture.fullValid(tempDir);
        config.put("port", "9200");
        config.put("httpPort", "9200");

        assertEquals(List.of("httpPort (9200) must be different from port (9200)"), entrypointErrors(config));
    }

    @Test
    public void test_http_port_equal_to_metrics_port_is_refused(@TempDir Path tempDir) {
        final var config = ConfigFixture.fullValid(tempDir);
        config.put("metricsPort", "9201");
        config.put("httpPort", "9201");

        assertEquals(List.of("httpPort (9201) must be different from metricsPort (9201)"), entrypointErrors(config));
    }

    @Test
    public void test_http_port_equal_to_cluster_port_is_refused_only_when_clustered(@TempDir Path tempDir) {
        final var config = ConfigFixture.fullValid(tempDir);
        config.put("clusterPort", "9202");
        config.put("httpPort", "9202");
        config.put("clusterEnabled", "false");
        assertEquals(List.of(), entrypointErrors(config));

        config.put("clusterEnabled", "true");
        assertEquals(List.of("httpPort (9202) must be different from clusterPort (9202)"), entrypointErrors(config));
    }

    @Test
    public void test_an_http_port_of_zero_never_collides(@TempDir Path tempDir) {
        final var config = ConfigFixture.fullValid(tempDir);
        config.put("httpPort", "0");
        config.put("metricsPort", "0");

        assertEquals(List.of(), entrypointErrors(config));
    }

    @Test
    public void test_entrypoint_timeout_above_max_is_refused(@TempDir Path tempDir) {
        final var config = ConfigFixture.fullValid(tempDir);
        config.put("entrypointTimeoutMs", "5001");
        config.put("entrypointMaxTimeoutMs", "5000");

        assertEquals(List.of("entrypointTimeoutMs (5001) must not be greater than entrypointMaxTimeoutMs (5000)"),
                entrypointErrors(config));
    }
}
