package org.ccg.mpp.unit.ops.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.conn.ClientTracker;
import org.ccg.mpp.data.admin.AdminCollEntry;
import org.ccg.mpp.ejson.EJson;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.fs.FileSystem;
import org.ccg.mpp.fs.RetiredCopies;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.ops.AdminOperationHelper;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.req.RequestParser;
import org.ccg.mpp.ops.resp.OperationResponse;
import org.ccg.mpp.ops.resp.ReadSnapshotResponse;
import org.ccg.mpp.ops.resp.StartSnapshotResponse;
import org.ccg.mpp.ops.snapshot.SnapshotRegistry;
import org.ccg.mpp.test.TestGlobals;
import org.ccg.mpp.test.TestUtils;

final class SnapshotTestSupport {
    static final String DB = TestGlobals.DB;
    static final String COLL = TestGlobals.COLL;
    static final String OTHER = "otherColl";
    static final String USER = "snapper";

    final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    final SnapshotRegistry registry = IocContainer.get(SnapshotRegistry.class);
    final FileSystem fs = IocContainer.get(FileSystem.class);
    final Cache cache = IocContainer.get(Cache.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final EJson eJson = IocContainer.get(EJson.class);
    private final List<UUID> clients = new ArrayList<>();
    UUID client;

    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(DB, OTHER));
        fs.createCollectionFile(DB, OTHER);
        TestUtils.ensureAdminUser(USER);
        fs.snapshotFiles().onRetiredCopies(registry);
        client = connect();
    }

    void tearDown() throws Exception {
        registry.releaseAll();
        fs.snapshotFiles().onRetiredCopies(RetiredCopies.NONE);
        clients.forEach(clientTracker::removeById);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    UUID connect() {
        final var clientId = clientTracker.addClient(TestUtils.clientSocket());
        clientTracker.setAuthenticatedUser(clientId, USER);
        clients.add(clientId);
        return clientId;
    }

    OperationResponse send(UUID clientId, String json) {
        return processor.processMessage(RequestParser.parseRequest(json), clientId);
    }

    OperationResponse send(String json) {
        return send(client, json);
    }

    void ok(String json) {
        final var response = send(json);
        assertEquals(OperationStatus.OK, response.getStatus(), response.getMessage());
    }

    void save(String id, Object value) {
        ok("{\"type\":\"SAVE\",\"databaseName\":\"" + DB + "\",\"collectionName\":\"" + COLL + "\",\"object\":{\""
                + Globals.PK_FIELD + "\":\"" + id + "\",\"v\":" + eJson.toJson(value) + "}}");
    }

    void delete(String coll, String id) {
        ok("{\"type\":\"DELETE\",\"databaseName\":\"" + DB + "\",\"collectionName\":\"" + coll + "\",\"_id\":\"" + id
                + "\"}");
    }

    String start() {
        final var response = send("{\"type\":\"START_SNAPSHOT\",\"databaseName\":\"" + DB + "\"}");
        assertEquals(OperationStatus.OK, response.getStatus(), response.getMessage());
        return ((StartSnapshotResponse) response).getSnapshotId();
    }

    OperationResponse read(UUID clientId, String snapshotId, String coll, String afterId, int limit) {
        return send(clientId,
                "{\"type\":\"READ_SNAPSHOT\",\"databaseName\":\"" + DB + "\",\"collectionName\":\"" + coll
                        + "\",\"snapshotId\":\"" + snapshotId + "\""
                        + (afterId == null ? "" : ",\"afterId\":\"" + afterId + "\"") + ",\"limit\":" + limit + "}");
    }

    ReadSnapshotResponse page(String snapshotId, String coll, String afterId, int limit) {
        final var response = read(client, snapshotId, coll, afterId, limit);
        assertEquals(OperationStatus.OK, response.getStatus(), response.getMessage());
        return (ReadSnapshotResponse) response;
    }

    List<JsonObject> readAll(String snapshotId, String coll, int limit) {
        final var documents = new ArrayList<JsonObject>();
        String afterId = null;
        while (true) {
            final var page = page(snapshotId, coll, afterId, limit);
            documents.addAll(page.getResults());
            if (page.isDone()) {
                return documents;
            }
            afterId = page.getLastId();
        }
    }

    List<String> summaries(List<JsonObject> documents) {
        return documents.stream().map(document -> document.get(Globals.PK_FIELD).asJsonString().getValue() + "="
                + eJson.toJson(document.get("v"))).toList();
    }

    OperationResponse release(UUID clientId, String snapshotId) {
        return send(clientId, "{\"type\":\"RELEASE_SNAPSHOT\",\"databaseName\":\"" + DB + "\",\"snapshotId\":\""
                + snapshotId + "\"}");
    }
}
