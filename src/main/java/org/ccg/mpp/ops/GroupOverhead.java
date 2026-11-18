package org.ccg.mpp.ops;

import java.util.ArrayList;
import org.ccg.mpp.cache.heap.HeapLayout;
import org.ccg.mpp.ejson.elements.JsonArray;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.utils.SaltedKey;

final class GroupOverhead {
    private static final int TABLE_SLOTS_PER_ENTRY = 2;
    static final long BYTES = bytesPerGroup(HeapLayout.current());

    private GroupOverhead() {
    }

    static long bytesPerGroup(HeapLayout layout) {
        return layout.shallowSize(SaltedKey.class) + layout.shallowSize(HeapLayout.jdkClass("java.util.HashMap$Node"))
                + layout.referenceArraySize(TABLE_SLOTS_PER_ENTRY) + layout.shallowSize(ArrayList.class)
                + layout.arrayListArraySize(1) + layout.shallowSize(JsonObject.class)
                + layout.shallowSize(JsonArray.class);
    }
}
