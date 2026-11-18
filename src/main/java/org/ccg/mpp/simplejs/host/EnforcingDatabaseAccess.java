package org.ccg.mpp.simplejs.host;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.ccg.mpp.cache.Cache;
import org.ccg.mpp.cluster.ClusterConfig;
import org.ccg.mpp.cluster.ClusterRouter;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.conn.ClientTracker;
import org.ccg.mpp.ejson.EJson;
import org.ccg.mpp.ejson.elements.JsonArray;
import org.ccg.mpp.ejson.elements.JsonObject;
import org.ccg.mpp.ejson.elements.JsonString;
import org.ccg.mpp.ex.InvalidCommandException;
import org.ccg.mpp.ioc.IocContainer;
import org.ccg.mpp.listen.ResultHasher;
import org.ccg.mpp.ops.ErrorCode;
import org.ccg.mpp.ops.OperationProcessor;
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.SchemaValidationHelper;
import org.ccg.mpp.ops.TransactionOperationHelper;
import org.ccg.mpp.ops.auth.AuthorizationChecker;
import org.ccg.mpp.ops.req.AggregateRequest;
import org.ccg.mpp.ops.req.BulkSaveRequest;
import org.ccg.mpp.ops.req.CommitTransactionRequest;
import org.ccg.mpp.ops.req.DeleteRequest;
import org.ccg.mpp.ops.req.FindByIdRequest;
import org.ccg.mpp.ops.req.ListCollectionsRequest;
import org.ccg.mpp.ops.req.ListDatabasesRequest;
import org.ccg.mpp.ops.req.OperationRequest;
import org.ccg.mpp.ops.req.RequestParser;
import org.ccg.mpp.ops.req.RollbackTransactionRequest;
import org.ccg.mpp.ops.req.SaveRequest;
import org.ccg.mpp.ops.req.StartTransactionRequest;
import org.ccg.mpp.ops.req.validations.RequestValidator;
import org.ccg.mpp.ops.resp.AggregateResponse;
import org.ccg.mpp.ops.resp.BulkSaveResponse;
import org.ccg.mpp.ops.resp.DeleteManyResponse;
import org.ccg.mpp.ops.resp.FindByIdResponse;
import org.ccg.mpp.ops.resp.ListCollectionsResponse;
import org.ccg.mpp.ops.resp.ListDatabasesResponse;
import org.ccg.mpp.ops.resp.OperationResponse;
import org.ccg.mpp.ops.resp.ResponseParser;
import org.ccg.mpp.ops.resp.SaveResponse;
import org.ccg.mpp.ops.resp.SaveWhereResponse;
import org.ccg.mpp.ops.tx.SliceStateMachine;
import org.ccg.mpp.simplejs.builtins.ErrorBuiltins;
import org.ccg.mpp.simplejs.builtins.Intrinsics;
import org.ccg.mpp.simplejs.exceptions.JsThrowException;

public final class EnforcingDatabaseAccess implements DatabaseAccess {
    private final OperationProcessor operationProcessor = IocContainer.get(OperationProcessor.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final ClusterRouter clusterRouter = IocContainer.get(ClusterRouter.class);
    private final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private final EJson eJson = IocContainer.get(EJson.class);

    private final AtomicLong dbOperations = new AtomicLong();

    private final String username;
    private final UUID clientId;
    private final String scopedDatabase;
    private final int triggerDepth;
    private Intrinsics intrinsics;
    private UUID sessionClientId;
    private Thread sessionThread;
    private volatile boolean lastCommitFenced;
    private volatile boolean clusterUnavailable;

    public EnforcingDatabaseAccess(String username, UUID clientId) {
        this(username, clientId, null);
    }

    public EnforcingDatabaseAccess(String username, UUID clientId, String scopedDatabase) {
        this(username, clientId, scopedDatabase, 0);
    }

    public EnforcingDatabaseAccess(String username, UUID clientId, String scopedDatabase, int triggerDepth) {
        this.username = username;
        this.clientId = clientId;
        this.scopedDatabase = scopedDatabase;
        this.triggerDepth = triggerDepth;
    }

    public long operationCount() {
        return dbOperations.get();
    }

    @Override
    public String scopedDatabase() {
        return scopedDatabase;
    }

    @Override
    public boolean mayReadProcedureSource() {
        return scopedDatabase != null
                && AuthorizationChecker.mayReadProcedureSource(cache.getAdminUserEntry(username), scopedDatabase);
    }

    @Override
    public JsonObject findById(String db, String coll, String id) {
        final var request = new FindByIdRequest(db, coll);
        request.set_id(id);
        final var response = dispatch(request);
        if (response instanceof FindByIdResponse findByIdResponse) {
            return findByIdResponse.getObject();
        }
        if (isNotFound(response)) {
            return null;
        }
        throw jsError(response.getMessage());
    }

    private static boolean isNotFound(OperationResponse response) {
        return ErrorCode.ENTRY_NOT_FOUND.getCode().equals(response.getErrorCode());
    }

    @Override
    public List<JsonObject> aggregate(String db, String coll, JsonArray pipeline) {
        final var rawJson = aggregateMessage(db, coll, pipeline);
        final var response = dispatch(parsedRequest(rawJson), rawJson);
        if (response instanceof AggregateResponse aggregateResponse) {
            return aggregateResponse.getResults();
        }
        if (ErrorCode.NO_RESULTS.getCode().equals(response.getErrorCode())) {
            return List.of();
        }
        throw jsError(response.getMessage());
    }

    private OperationRequest parsedRequest(String rawJson) {
        try {
            return RequestParser.parseRequest(rawJson);
        } catch (InvalidCommandException unparseable) {
            throw jsError(unparseable.getMessage());
        }
    }

    @Override
    public boolean ordersResults(String db, String coll, JsonArray pipeline) {
        try {
            return !(RequestParser
                    .parseRequest(aggregateMessage(db, coll, pipeline)) instanceof AggregateRequest parsed)
                    || ResultHasher.ordersResults(parsed.getAggregationSteps());
        } catch (RuntimeException unparseable) {
            return true;
        }
    }

    @Override
    public int firstUnorderedCut(String db, String coll, JsonArray pipeline) {
        try {
            return RequestParser.parseRequest(aggregateMessage(db, coll, pipeline)) instanceof AggregateRequest parsed
                    ? ResultHasher.firstUnorderedCut(parsed.getAggregationSteps())
                    : -1;
        } catch (RuntimeException unparseable) {
            return -1;
        }
    }

    private String aggregateMessage(String db, String coll, JsonArray pipeline) {
        final var message = new JsonObject();
        message.add("type", new JsonString("AGGREGATE"));
        message.add("databaseName", new JsonString(db));
        message.add("collectionName", new JsonString(coll));
        message.add("aggregationSteps", pipeline);
        return eJson.toJson(message);
    }

    @Override
    public JsonObject save(String db, String coll, JsonObject document) {
        return saveDocument(db, coll, document, false);
    }

    @Override
    public JsonObject insert(String db, String coll, JsonObject document) {
        return saveDocument(db, coll, document, true);
    }

    private JsonObject saveDocument(String db, String coll, JsonObject document, boolean failOnUpdate) {
        final var request = new SaveRequest(db, coll);
        request.setObject(document);
        request.setFailOnUpdate(failOnUpdate);
        if (document.get(Globals.PK_FIELD) instanceof JsonString objectId) {
            request.set_id(objectId.getValue());
        }
        final var response = dispatch(request);
        if (response instanceof SaveResponse saveResponse) {
            return findById(db, coll, saveResponse.get_id());
        }
        throw jsError(response.getMessage());
    }

    @Override
    public BulkSaveOutcome bulkSave(String db, String coll, List<JsonObject> documents) {
        final var request = new BulkSaveRequest(db, coll);
        request.setObjects(documents);
        final var response = dispatch(request);
        if (response instanceof BulkSaveResponse bulkSaveResponse) {
            return new BulkSaveOutcome(bulkSaveResponse.getInserted(), bulkSaveResponse.getUpdated());
        }
        throw jsError(response.getMessage());
    }

    @Override
    public void delete(String db, String coll, String id) {
        final var request = new DeleteRequest(db, coll);
        request.set_id(id);
        final var response = dispatch(request);
        if (response.getStatus() != OperationStatus.OK && !isNotFound(response)) {
            throw jsError(response.getMessage());
        }
    }

    @Override
    public long deleteWhere(String db, String coll, JsonObject condition) {
        final var message = new JsonObject();
        message.add("type", new JsonString("DELETE"));
        message.add("databaseName", new JsonString(db));
        message.add("collectionName", new JsonString(coll));
        message.add("condition", condition);
        final var rawJson = eJson.toJson(message);
        final var response = dispatch(parsedRequest(rawJson), rawJson);
        if (response instanceof DeleteManyResponse deleteManyResponse) {
            return deleteManyResponse.getDeletedCount();
        }
        throw jsError(response.getMessage());
    }

    @Override
    public long updateWhere(String db, String coll, JsonObject condition, JsonObject patch) {
        final var message = new JsonObject();
        message.add("type", new JsonString("SAVE"));
        message.add("databaseName", new JsonString(db));
        message.add("collectionName", new JsonString(coll));
        message.add("condition", condition);
        message.add("object", patch);
        final var rawJson = eJson.toJson(message);
        final var response = dispatch(parsedRequest(rawJson), rawJson);
        if (response instanceof SaveWhereResponse saveWhereResponse) {
            return saveWhereResponse.getUpdatedCount();
        }
        throw jsError(response.getMessage());
    }

    @Override
    public List<String> listCollections(String db) {
        final var response = dispatch(new ListCollectionsRequest(db));
        if (response instanceof ListCollectionsResponse listCollectionsResponse) {
            return listCollectionsResponse.getCollections();
        }
        throw jsError(response.getMessage());
    }

    @Override
    public List<String> listDatabases() {
        if (scopedDatabase != null) {
            return List.of(scopedDatabase);
        }
        final var response = dispatch(new ListDatabasesRequest());
        if (response instanceof ListDatabasesResponse listDatabasesResponse) {
            return listDatabasesResponse.getDatabases();
        }
        throw jsError(response.getMessage());
    }

    @Override
    public void beginTransaction() {
        if (sessionClientId != null) {
            throw jsError("A transaction is already active on this script");
        }
        sessionThread = Thread.currentThread();
        sessionClientId = clientId != null ? clientId : clientTracker.registerForwardedClient(username);
        try {
            requireOk(dispatch(new StartTransactionRequest()));
        } catch (RuntimeException e) {
            clearSession();
            throw e;
        }
    }

    @Override
    public void commitTransaction() {
        finishTransaction(new CommitTransactionRequest());
    }

    @Override
    public void rollbackTransaction() {
        finishTransaction(new RollbackTransactionRequest());
    }

    private void finishTransaction(OperationRequest request) {
        assertSessionThread();
        if (sessionClientId == null) {
            throw jsError("No transaction is active on this script");
        }
        final var transaction = clientTracker.getActiveTransaction(sessionClientId);
        final var txId = transaction != null ? transaction.getTransactionId().toString() : null;
        OperationResponse response = null;
        try {
            response = dispatch(request);
        } finally {
            final var fenced = txId != null && (SliceStateMachine.isFenced(txId) || keptAfterFinishing(response));
            lastCommitFenced = fenced;
            if (response == null && !fenced) {
                TransactionOperationHelper.rollback(sessionClientId);
            }
            clearSession(fenced);
        }
        if (response.getStatus() != OperationStatus.OK
                && !ErrorCode.REPLICATION_TIMEOUT.getCode().equals(response.getErrorCode())) {
            throw jsError(response.getMessage());
        }
    }

    private boolean keptAfterFinishing(OperationResponse response) {
        return response != null && clientTracker.getActiveTransaction(sessionClientId) != null;
    }

    public void bufferTriggerRunConsume(String runId) {
        assertSessionThread();
        final var transaction = clientTracker.getActiveTransaction(sessionClientId);
        if (transaction == null) {
            throw jsError("No transaction is active on this script");
        }
        try {
            TransactionOperationHelper.bufferTriggerRunConsume(transaction, runId);
            clientTracker.markLocalSlice(sessionClientId);
        } catch (Exception e) {
            throw jsError("Could not consume the pending trigger run: " + e.getMessage());
        }
    }

    public boolean hasActiveTransaction() {
        return sessionClientId != null;
    }

    public boolean transactionBufferedNoWrites() {
        final var transaction = sessionClientId == null ? null : clientTracker.getActiveTransaction(sessionClientId);
        return transaction != null && transaction.getPendingOps().isEmpty() && transaction.getHeldLocks().isEmpty()
                && clientTracker.transactionParticipants(sessionClientId).isEmpty();
    }

    public boolean lastCommitWasFenced() {
        return lastCommitFenced;
    }

    public boolean sawClusterUnavailable() {
        return clusterUnavailable;
    }

    private void clearSession() {
        clearSession(false);
    }

    private void clearSession(boolean fenced) {
        if (sessionClientId != null && !fenced) {
            clientTracker.clearTransactionState(sessionClientId);
            if (!sessionClientId.equals(clientId)) {
                clientTracker.removeById(sessionClientId);
            }
        }
        sessionClientId = null;
        sessionThread = null;
    }

    private void requireOk(OperationResponse response) {
        if (response.getStatus() != OperationStatus.OK) {
            throw jsError(response.getMessage());
        }
    }

    private void assertSessionThread() {
        if (sessionThread != null && sessionThread != Thread.currentThread()) {
            throw jsError("A script transaction may only be used from the thread that started it");
        }
    }

    private OperationResponse dispatch(OperationRequest request) {
        return dispatch(request, null);
    }

    private OperationResponse dispatch(OperationRequest request, String rawJson) {
        assertSessionThread();
        dbOperations.incrementAndGet();
        request.setTriggerDepth(triggerDepth);
        if (scopedDatabase != null) {
            final var targetDatabase = request.getDatabaseName();
            if (targetDatabase != null && !scopedDatabase.equals(targetDatabase)) {
                throw jsError("This script may only access database '" + scopedDatabase + "'");
            }
        }
        final var validation = RequestValidator.validate(request);
        if (!validation.isValid()) {
            throw jsError(validation.getErrorMessage());
        }
        if (!isTransactionControl(request)) {
            final var user = cache.getAdminUserEntry(username);
            if (user == null) {
                throw jsError("User '" + username + "' not found");
            }
            final var authorization = AuthorizationChecker.check(request, user);
            if (!authorization.isAllowed()) {
                throw jsError(authorization.getReason());
            }
        }
        final var schemaError = SchemaValidationHelper.check(request);
        if (schemaError != null) {
            throw jsError(schemaError.getMessage());
        }
        if (sessionClientId != null) {
            return recordClusterAvailability(routeOrProcess(request, rawJson, sessionClientId));
        }
        if (clientId != null) {
            return recordClusterAvailability(routeOrProcess(request, rawJson, clientId));
        }
        final var forwardedClientId = clientTracker.registerForwardedClient(username);
        try {
            return recordClusterAvailability(routeOrProcess(request, rawJson, forwardedClientId));
        } finally {
            clientTracker.removeById(forwardedClientId);
        }
    }

    private OperationResponse recordClusterAvailability(OperationResponse response) {
        if (ErrorCode.NO_QUORUM.getCode().equals(response.getErrorCode())
                || ErrorCode.NOT_COLLECTION_OWNER.getCode().equals(response.getErrorCode())) {
            clusterUnavailable = true;
        }
        return response;
    }

    private OperationResponse routeOrProcess(OperationRequest request, String rawJson, UUID effectiveClientId) {
        if (clusterConfig.isEnabled()) {
            final var forwarded = clusterRouter.forward(request, rawJson != null ? rawJson : eJson.toJson(request),
                    sessionClientId != null, username, effectiveClientId);
            if (forwarded != null) {
                try {
                    return ResponseParser.parseResponse(forwarded);
                } catch (RuntimeException e) {
                    throw jsError("Unreadable response from the collection's owner: " + e.getMessage());
                }
            }
        }
        return operationProcessor.processMessage(request, effectiveClientId);
    }

    private static boolean isTransactionControl(OperationRequest request) {
        return request instanceof StartTransactionRequest || request instanceof CommitTransactionRequest
                || request instanceof RollbackTransactionRequest;
    }

    @Override
    public void useIntrinsics(Intrinsics value) {
        this.intrinsics = value;
    }

    private JsThrowException jsError(String message) {
        return new JsThrowException(intrinsics == null
                ? ErrorBuiltins.makeError("Error", message)
                : intrinsics.makeError("Error", message));
    }
}
