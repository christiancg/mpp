package org.ccg.mpp.fs;

import java.io.IOException;
import java.util.List;

public interface RetiredCopies {
    RetiredCopies NONE = new RetiredCopies() {
        @Override
        public boolean watches(String projectName) {
            return false;
        }

        @Override
        public void retiring(String projectName, String collName, List<RetiredCopy> retired, List<String> born,
                OldLines oldLines) {
        }
    };

    boolean watches(String projectName);

    void retiring(String projectName, String collName, List<RetiredCopy> retired, List<String> born, OldLines oldLines);

    record RetiredCopy(String id, long page, long offset, long length) {
    }

    @FunctionalInterface
    interface OldLines {
        byte[] read(RetiredCopy copy) throws IOException;
    }
}
