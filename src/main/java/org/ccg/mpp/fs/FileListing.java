package org.ccg.mpp.fs;

import java.util.List;
import org.ccg.mpp.data.FileEntry;

public record FileListing(List<Listed> entries, String next) {
    public record Listed(String path, FileEntry entry) {
    }
}
