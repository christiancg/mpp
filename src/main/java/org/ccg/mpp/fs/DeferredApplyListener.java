package org.ccg.mpp.fs;

import java.util.List;
import org.ccg.mpp.data.PkIndexEntry;

public interface DeferredApplyListener {
    DeferredApplyListener NONE = new DeferredApplyListener() {
        @Override
        public long replaying(String projectName, String collName, List<PkIndexEntry> rows) {
            return 0L;
        }

        @Override
        public void replayed(String projectName, String collName, List<PkIndexEntry> rows, long generation) {
        }

        @Override
        public void notReplayed(String projectName, String collName, List<PkIndexEntry> rows, long generation) {
        }
    };

    long replaying(String projectName, String collName, List<PkIndexEntry> rows);

    void replayed(String projectName, String collName, List<PkIndexEntry> rows, long generation);

    void notReplayed(String projectName, String collName, List<PkIndexEntry> rows, long generation);
}
