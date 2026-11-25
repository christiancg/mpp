package io.github.christiancg.mpp.client.files;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class FileRange {
    private final String path;
    private final String version;
    private final long size;
    private final long offset;
    private final byte[] content;
    private final boolean eof;

    public FileRange(String path, String version, long size, long offset, byte[] content, boolean eof) {
        this.path = Objects.requireNonNull(path, "path");
        this.version = Objects.requireNonNull(version, "version");
        this.size = size;
        this.offset = offset;
        this.content = content.clone();
        this.eof = eof;
    }

    public String path() {
        return path;
    }

    public String version() {
        return version;
    }

    public long size() {
        return size;
    }

    public long offset() {
        return offset;
    }

    public byte[] content() {
        return content.clone();
    }

    public int length() {
        return content.length;
    }

    public String text() {
        return new String(content, StandardCharsets.UTF_8);
    }

    public boolean eof() {
        return eof;
    }
}
