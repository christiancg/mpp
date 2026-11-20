package org.ccg.mpp.cluster.admin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.cluster.HybridClock;
import org.ccg.mpp.config.Configuration;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.data.DbEntry;
import org.ccg.mpp.data.admin.AdminCollEntry;
import org.ccg.mpp.data.admin.AdminProjectEntry;
import org.ccg.mpp.data.admin.AdminUserEntry;
import org.ccg.mpp.data.auth.PermissionLevel;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.admin.AdminRecordKey;
import org.ccg.mpp.ops.admin.AdminStamp;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

abstract class AdminRecordTestBase {
    protected final Cache cache = IocContainer.get(Cache.class);
    protected final FileSystem fs = IocContainer.get(FileSystem.class);
    protected final HybridClock clock = IocContainer.get(HybridClock.class);
    protected final Configuration config = Configuration.getInstance();
    private boolean origEnabled;

    @BeforeEach
    public void setUpAdminRecords() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestProjectAndCollection();
        origEnabled = config.isClusterEnabled();
        TestUtils.setPrivateField(config, "clusterEnabled", true);
    }

    @AfterEach
    public void tearDownAdminRecords() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", origEnabled);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    protected static JsonObject bodyOf(DbEntry entry) {
        final var body = entry.getData().deepCopy();
        body.addProperty(Globals.PK_FIELD, entry.get_id());
        return body;
    }

    protected AdminRecord project(String projectName, long version, String... owners) {
        return new AdminRecord(AdminRecordKey.project(projectName), version,
                bodyOf(new AdminProjectEntry(projectName, new ArrayList<>(), new ArrayList<>(List.of(owners)))));
    }

    protected AdminRecord collection(String projectName, String collName, long version, String... indexes) {
        return collection(projectName, collName, version, 0L, indexes);
    }

    protected AdminRecord collection(String projectName, String collName, long version, long incarnation,
            String... indexes) {
        final var entry = new AdminCollEntry(projectName, collName, Set.of(indexes));
        entry.setIncarnation(incarnation);
        return new AdminRecord(AdminRecordKey.collection(projectName, collName), version, bodyOf(entry));
    }

    protected AdminRecord user(String username, long version) {
        return user(username, version, new HashMap<>());
    }

    protected AdminRecord user(String username, long version, Map<String, PermissionLevel> projectGrants) {
        return new AdminRecord(AdminRecordKey.user(username), version, bodyOf(
                new AdminUserEntry(username, "hash-" + username, false, Set.of(), projectGrants, new HashMap<>())));
    }

    protected AdminRecord procedure(String projectName, String name, long version) {
        final var body = new JsonObject();
        body.addProperty("name", name);
        body.addProperty("source", "return 1;");
        body.addProperty("writeVersion", Long.toString(version));
        return new AdminRecord(AdminRecordKey.procedure(projectName, name), version, body);
    }

    protected AdminRecord schema(String collName, long version) {
        final var schema = new JsonObject();
        schema.addProperty("type", "object");
        final var body = new JsonObject();
        body.add("schema", schema);
        body.addProperty("writeVersion", Long.toString(version));
        return new AdminRecord(AdminRecordKey.schema(TestGlobals.PROJECT, collName), version, body);
    }

    protected static AdminRecord tombstone(AdminRecordKey key, long version) {
        return AdminRecord.tombstone(key, version);
    }

    protected static Map<String, Long> tombstones() throws Exception {
        return AdminStamp.tombstones();
    }

    protected static long liveVersion(AdminRecordKey key) {
        return Objects.requireNonNull(AdminRecords.live(key)).version();
    }
}
