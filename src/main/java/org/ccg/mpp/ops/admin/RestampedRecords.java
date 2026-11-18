package org.ccg.mpp.ops.admin;

import java.util.ArrayList;
import java.util.List;

public final class RestampedRecords {
    private final List<AdminRecordKey> keys = new ArrayList<>();

    public void add(AdminRecordKey key) {
        keys.add(key);
    }

    public List<AdminRecordKey> keys() {
        return List.copyOf(keys);
    }
}
