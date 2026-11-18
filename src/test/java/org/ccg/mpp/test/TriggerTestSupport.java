package org.ccg.mpp.test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.data.DbEntry;
import org.ccg.mpp.data.admin.AdminTriggerRunEntry;
import org.ccg.mpp.data.admin.TriggerRunStatus;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.CompiledProcedureCache;
import org.ccg.mpp.ops.TriggerDispatcher;
import org.ccg.mpp.ops.TriggerRunLog;
import org.ccg.mpp.ops.UserOperationHelper;
import org.ccg.mpp.ops.req.CreateUserRequest;

public final class TriggerTestSupport {
    private TriggerTestSupport() {
    }

    public static void createDefiner(String username) throws Exception {
        AdminOperationHelper.updateDatabaseOwners(TestGlobals.DB, List.of(username));
        createUser(username);
    }

    public static void createUser(String username) {
        final var request = new CreateUserRequest();
        request.setUsername(username);
        request.setPassword("password123");
        request.setAdmin(false);
        request.setGlobalPermissions(new HashSet<>());
        request.setDatabasePermissions(new HashMap<>());
        request.setCollectionPermissions(new HashMap<>());
        UserOperationHelper.processCreateUser(request);
    }

    public static void clearTriggersAndProcedures() throws Exception {
        final var cache = IocContainer.get(Cache.class);
        final var fs = IocContainer.get(FileSystem.class);
        cache.removeTriggers(TestGlobals.DB, TestGlobals.COLL);
        for (final var name : fs.metadataFiles().listProcedureNames(TestGlobals.DB)) {
            fs.metadataFiles().deleteProcedure(TestGlobals.DB, name);
        }
        cache.removeProceduresForDatabase(TestGlobals.DB);
        IocContainer.get(CompiledProcedureCache.class).invalidateDatabase(TestGlobals.DB);
    }

    public static void consumeAllPendingRuns() throws Exception {
        for (final var entry : TriggerRunLog.pending()) {
            TriggerDispatcher.consumeQuietly(entry.getRunId(), entry.getTriggerName());
        }
    }

    public static AdminTriggerRunEntry pendingRun(String runId) throws Exception {
        for (final var run : TriggerRunLog.pending()) {
            if (runId.equals(run.getRunId())) {
                return run;
            }
        }
        return null;
    }

    public static TriggerRunStatus statusOf(String runId) throws Exception {
        final var run = pendingRun(runId);
        return run == null ? null : run.getStatus();
    }

    public static DbEntry entry(String id) {
        final var data = new JsonObject();
        data.add("_id", new JsonString(id));
        return entryOf(id, data);
    }

    public static DbEntry entry(String id, String value) {
        final var data = new JsonObject();
        data.add("_id", new JsonString(id));
        data.add("value", new JsonString(value));
        return entryOf(id, data);
    }

    private static DbEntry entryOf(String id, JsonObject data) {
        final var dbEntry = new DbEntry();
        dbEntry.setDatabaseName(TestGlobals.DB);
        dbEntry.setCollectionName(TestGlobals.COLL);
        dbEntry.set_id(id);
        dbEntry.setData(data);
        return dbEntry;
    }
}
