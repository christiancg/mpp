package org.ccg.mpp.fs;

import java.util.Collection;
import java.util.List;

public interface CheckpointGuard {
    CheckpointGuard NO_INDEX_WORK = new CheckpointGuard() {
        @Override
        public boolean holdsIndexWork(String projectName, String collName) {
            return false;
        }

        @Override
        public Collection<String> pendingIds(String projectName, String collName) {
            return List.of();
        }
    };

    boolean holdsIndexWork(String projectName, String collName);

    Collection<String> pendingIds(String projectName, String collName);
}
