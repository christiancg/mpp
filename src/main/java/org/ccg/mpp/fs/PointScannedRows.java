package org.ccg.mpp.fs;

import java.util.AbstractList;
import java.util.RandomAccess;
import org.ccg.mpp.data.PkIndexEntry;

public final class PointScannedRows extends AbstractList<PkIndexEntry> implements RandomAccess {
    public static final PointScannedRows INSTANCE = new PointScannedRows();

    private PointScannedRows() {
    }

    @Override
    public PkIndexEntry get(int index) {
        throw new IndexOutOfBoundsException(index);
    }

    @Override
    public int size() {
        return 0;
    }
}
