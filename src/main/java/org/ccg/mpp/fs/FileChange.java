package org.ccg.mpp.fs;

public record FileChange(Type type, String path, long version, long size, long baseVersion, long baseSize,
        String target) {
    public enum Type {
        FILE, APPEND, DIRECTORY, TOMBSTONE, MOVE
    }

    static FileChange file(String path, long version, long size) {
        return new FileChange(Type.FILE, path, version, size, 0L, 0L, null);
    }

    static FileChange append(String path, long version, long size, long baseVersion, long baseSize) {
        return new FileChange(Type.APPEND, path, version, size, baseVersion, baseSize, null);
    }

    static FileChange directory(String path, long version) {
        return new FileChange(Type.DIRECTORY, path, version, 0L, 0L, 0L, null);
    }

    static FileChange tombstone(String path, long version) {
        return new FileChange(Type.TOMBSTONE, path, version, 0L, 0L, 0L, null);
    }

    static FileChange move(String from, String to, long version, int moved) {
        return new FileChange(Type.MOVE, from, version, moved, 0L, 0L, to);
    }
}
