package org.ccg.mpp.config;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class FilesPathRules {
    private FilesPathRules() {
    }

    static void validate(Map<String, String> configs, List<String> errors) {
        final var filesKey = ConfigKey.FILES_PATH.key();
        final var withDefault = new HashMap<>(configs);
        withDefault.putIfAbsent(filesKey, ConfigKey.FILES_PATH.defaultValue());
        final var before = errors.size();
        ConfigurationValidator.validateWritablePath(withDefault, filesKey, errors);
        if (errors.size() > before) {
            return;
        }
        final var files = normalized(withDefault.get(filesKey));
        refuseOverlap(files, configs.get(ConfigKey.FILE_PATH.key()), ConfigKey.FILE_PATH, errors);
        refuseOverlap(files, configs.get(ConfigKey.LOG_PATH.key()), ConfigKey.LOG_PATH, errors);
    }

    private static void refuseOverlap(Path files, String other, ConfigKey otherKey, List<String> errors) {
        if (other == null || other.isBlank()) {
            return;
        }
        final var otherPath = normalized(other);
        if (files.startsWith(otherPath) || otherPath.startsWith(files)) {
            errors.add(ConfigKey.FILES_PATH.key() + " (" + files + ") must neither equal, contain nor sit inside "
                    + otherKey.key() + " (" + otherPath + ")");
        }
    }

    private static Path normalized(String value) {
        return Path.of(value.trim()).toAbsolutePath().normalize();
    }
}
