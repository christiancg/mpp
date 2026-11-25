package org.ccg.mpp.data;

import java.util.Locale;

public enum FileWriteMode {
    REPLACE, CREATE, APPEND;

    public static FileWriteMode parse(String text) {
        for (final var mode : values()) {
            if (mode.name().equals(text.toUpperCase(Locale.ROOT))) {
                return mode;
            }
        }
        return null;
    }
}
