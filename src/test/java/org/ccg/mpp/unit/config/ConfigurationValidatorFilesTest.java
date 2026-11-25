package org.ccg.mpp.unit.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.ccg.mpp.config.ConfigurationValidator;
import org.ccg.mpp.test.ConfigFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ConfigurationValidatorFilesTest {
    private static boolean mentions(List<String> errors, String text) {
        return errors.stream().anyMatch(error -> error.contains(text));
    }

    @Test
    public void test_a_separate_files_folder_is_valid(@TempDir Path tempDir) {
        final var config = ConfigFixture.fullValid(tempDir.resolve("db"));
        config.put("logPath", tempDir.resolve("logs").toString());
        config.put("filesPath", tempDir.resolve("files").toString());
        assertTrue(ConfigurationValidator.validate(config).isEmpty(),
                ConfigurationValidator.validate(config).toString());
    }

    @Test
    public void test_files_inside_equal_to_or_around_the_data_folder_are_refused(@TempDir Path tempDir) {
        for (final var files : List.of(tempDir.resolve("db/files"), tempDir.resolve("db"), tempDir)) {
            final var config = ConfigFixture.fullValid(tempDir.resolve("db"));
            config.put("logPath", tempDir.resolve("logs").toString());
            config.put("filesPath", files.toString());
            assertTrue(mentions(ConfigurationValidator.validate(config), "filePath"), files.toString());
        }
    }

    @Test
    public void test_files_overlapping_the_log_folder_are_refused(@TempDir Path tempDir) {
        final var config = ConfigFixture.fullValid(tempDir.resolve("db"));
        config.put("logPath", tempDir.resolve("logs").toString());
        config.put("filesPath", tempDir.resolve("logs/files").toString());
        assertTrue(mentions(ConfigurationValidator.validate(config), "logPath"));
    }

    @Test
    public void test_a_blank_files_folder_is_refused(@TempDir Path tempDir) {
        final var config = ConfigFixture.fullValid(tempDir);
        config.put("filesPath", " ");
        assertTrue(mentions(ConfigurationValidator.validate(config), "filesPath"));
    }
}
