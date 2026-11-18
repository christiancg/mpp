package org.ccg.mpp.fs;

import java.util.List;
import org.ccg.mpp.data.PkIndexEntry;

public record PkRebuild(List<PkIndexEntry> rows, int unversioned, boolean previousReadable, List<PkIndexEntry> appeared,
        List<PkIndexEntry> vanished) {
    public int entries() {
        return rows.size();
    }
}
