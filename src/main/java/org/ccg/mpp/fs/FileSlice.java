package org.ccg.mpp.fs;

import org.ccg.mpp.data.FileEntry;

public record FileSlice(FileEntry entry, long offset, byte[] bytes) {
    public boolean reachesEnd() {
        return offset + bytes.length >= entry.size();
    }
}
