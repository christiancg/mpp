package org.ccg.mpp.data;

import org.ccg.mpp.ejson.WireName;

public enum FileEncoding {
    @WireName("base64")
    BASE64, @WireName("utf8")
    UTF8;

    public static FileEncoding parse(String text) {
        for (final var encoding : values()) {
            if (encoding.name().equalsIgnoreCase(text)) {
                return encoding;
            }
        }
        return null;
    }

    public static FileEncoding orDefault(FileEncoding requested) {
        return requested == null ? BASE64 : requested;
    }
}
