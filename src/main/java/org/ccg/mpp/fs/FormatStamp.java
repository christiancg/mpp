package org.ccg.mpp.fs;

import org.ccg.mpp.config.Globals;
import org.ccg.mpp.config.ServerVersion;

public record FormatStamp(int formatVersion, String writtenBy) {
    public static FormatStamp current() {
        return new FormatStamp(Globals.STORAGE_FORMAT_VERSION, ServerVersion.current());
    }
}
