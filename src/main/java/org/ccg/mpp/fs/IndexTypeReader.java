package org.ccg.mpp.fs;

import java.io.IOException;
import java.util.List;
import org.ccg.mpp.data.FieldIndexEntry;

@FunctionalInterface
public interface IndexTypeReader {
    List<? extends FieldIndexEntry<?>> read() throws IOException;
}
