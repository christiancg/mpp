package org.ccg.mpp.unit.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.ccg.mpp.config.ConfigurationValidator;
import org.ccg.mpp.test.ConfigFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class PowerLossShadowConfigTest {

    @Test
    public void test_a_shadow_path_with_fault_injection_outside_file_path_is_valid(@TempDir Path tempDir) {
        final var config = ConfigFixture.fullValid(tempDir.resolve("data"));
        config.put("faultInjectionEnabled", "true");
        config.put("powerLossShadowPath", tempDir.resolve("shadow").toString());
        assertTrue(ConfigurationValidator.validate(config).isEmpty());
    }

    @Test
    public void test_a_shadow_path_without_fault_injection_refuses_to_start(@TempDir Path tempDir) {
        final var config = ConfigFixture.fullValid(tempDir.resolve("data"));
        config.put("faultInjectionEnabled", "false");
        config.put("powerLossShadowPath", tempDir.resolve("shadow").toString());
        assertMentions(ConfigurationValidator.validate(config), "requires faultInjectionEnabled=true");
    }

    @Test
    public void test_a_shadow_path_inside_file_path_refuses_to_start(@TempDir Path tempDir) {
        final var config = ConfigFixture.fullValid(tempDir.resolve("data"));
        config.put("faultInjectionEnabled", "true");
        config.put("powerLossShadowPath", tempDir.resolve("data").resolve("shadow").toString());
        assertMentions(ConfigurationValidator.validate(config), "must lie outside filePath");
    }

    @Test
    public void test_a_blank_shadow_path_is_off(@TempDir Path tempDir) {
        final var config = ConfigFixture.fullValid(tempDir);
        config.put("powerLossShadowPath", "  ");
        assertTrue(ConfigurationValidator.validate(config).isEmpty());
    }

    private static void assertMentions(List<String> errors, String fragment) {
        assertTrue(errors.stream().anyMatch(error -> error.contains(fragment)), errors.toString());
    }
}
