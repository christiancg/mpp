package org.ccg.mpp.cache;

import java.io.IOException;
import java.util.List;
import org.ccg.mpp.data.PkIndexEntry;

@FunctionalInterface
public interface PkRows {
    List<PkIndexEntry> rows(String dbName, String collName) throws IOException;
}
