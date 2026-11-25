package org.ccg.mpp.data;

public record FileEntry(FileKind kind, long version, long size) {
    public static FileEntry file(long version, long size) {
        return new FileEntry(FileKind.FILE, version, size);
    }

    public static FileEntry directory(long version) {
        return new FileEntry(FileKind.DIRECTORY, version, 0L);
    }

    public boolean isDirectory() {
        return kind == FileKind.DIRECTORY;
    }
}
