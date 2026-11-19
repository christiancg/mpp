package org.ccg.mpp.fs;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.ccg.mpp.data.PkIndexEntry;

public final class PkRowsSession {
    private final PkIndexStore store;
    private final String projectName;
    private final String collName;
    private boolean loaded;
    private List<PkIndexEntry> recognisedRows;

    PkRowsSession(PkIndexStore store, String projectName, String collName) {
        this.store = store;
        this.projectName = projectName;
        this.collName = collName;
    }

    PkIndexStore store() {
        return store;
    }

    String projectName() {
        return projectName;
    }

    String collName() {
        return collName;
    }

    public Optional<List<PkIndexEntry>> recognised() throws IOException {
        if (!loaded) {
            recognisedRows = store.readRecognisedPkIndex(projectName, collName).orElse(null);
            loaded = true;
        }
        return Optional.ofNullable(recognisedRows);
    }

    public List<PkIndexEntry> rows() throws IOException {
        final var rows = recognised();
        return rows.isPresent() ? rows.get() : store.readWholePkIndexFile(projectName, collName);
    }

    public void invalidate() {
        loaded = false;
        recognisedRows = null;
    }
}
