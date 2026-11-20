package org.ccg.mpp.unit.ops.index;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.ccg.mpp.bckg_ops.BackgroundTaskManager;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.concurrency.ResourceLocking;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.IndexHelper;
import org.ccg.mpp.ops.SaveOperationHelper;
import org.ccg.mpp.ops.req.BulkSaveRequest;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;

public final class DueFoldFixture {
    private static final int IDS = 12;
    private static final String PADDING = "p".repeat(2000);

    private final Cache cache = IocContainer.get(Cache.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final BackgroundTaskManager taskManager = IocContainer.get(BackgroundTaskManager.class);

    public record Marked(long from, long by) {
    }

    private static List<String> ids() {
        final var ids = new ArrayList<String>(IDS);
        for (var i = 0; i < IDS; i++) {
            ids.add("id" + i);
        }
        return ids;
    }

    public void saveRound(List<String> fields, int round) throws Exception {
        final var objects = new ArrayList<JsonObject>(IDS);
        for (final var id : ids()) {
            final var doc = new JsonObject();
            doc.add(Globals.PK_FIELD, new JsonString(id));
            for (final var field : fields) {
                doc.add(field, new JsonString(PADDING + round + "-" + id));
            }
            objects.add(doc);
        }
        final var request = new BulkSaveRequest(TestGlobals.PROJECT, TestGlobals.COLL);
        request.setObjects(objects);
        locks.lock(TestGlobals.PROJECT, TestGlobals.COLL);
        try {
            SaveOperationHelper.executeBulkSave(request);
        } finally {
            locks.release(TestGlobals.PROJECT, TestGlobals.COLL);
        }
        TestUtils.discardQueuedBackgroundEvents(taskManager);
        IndexHelper.bulkUpdateIndexes(TestGlobals.PROJECT, TestGlobals.COLL, ids());
    }

    public Marked markDue(List<String> fields) throws Exception {
        saveRound(fields, 0);
        for (final var field : fields) {
            IndexHelper.createIndex(TestGlobals.PROJECT, TestGlobals.COLL, field);
        }
        cache.getAdminCollectionEntry(TestGlobals.PROJECT, TestGlobals.COLL).setIndexes(Set.copyOf(fields));
        saveRound(fields, 1);
        final var from = System.currentTimeMillis();
        saveRound(fields, 2);
        final var marked = new Marked(from, System.currentTimeMillis());
        for (final var field : fields) {
            assertTrue(logOf(field).length() >= Globals.FIELD_INDEX_LOG_MIN_BYTES, "the log of " + field + " is due");
        }
        return marked;
    }

    public static File logOf(String field) {
        return new File(TestGlobals.PATH + File.separator + TestGlobals.PROJECT + File.separator + TestGlobals.COLL,
                TestGlobals.COLL + "-" + field + "-String" + Globals.INDEX_LOG_FILE_EXTENSION);
    }
}
