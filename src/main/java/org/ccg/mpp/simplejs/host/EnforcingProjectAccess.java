package org.ccg.mpp.simplejs.host;

import java.util.List;
import java.util.UUID;
import org.ccg.mpp.cache.Cache;
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
import org.ccg.mpp.ops.OperationStatus;
import org.ccg.mpp.ops.TransactionOperationHelper;
import org.ccg.mpp.ops.auth.AuthorizationChecker;
import org.ccg.mpp.ops.req.AggregateRequest;
import org.ccg.mpp.ops.req.BulkSaveRequest;
import org.ccg.mpp.ops.req.CommitTransactionRequest;
import org.ccg.mpp.ops.req.DeleteRequest;
import org.ccg.mpp.ops.req.FindByIdRequest;
import org.ccg.mpp.ops.req.ListCollectionsRequest;
import org.ccg.mpp.ops.req.ListProjectsRequest;
import org.ccg.mpp.ops.req.OperationRequest;
import org.ccg.mpp.ops.req.RequestParser;
import org.ccg.mpp.ops.req.RollbackTransactionRequest;
import org.ccg.mpp.ops.req.SaveRequest;
import org.ccg.mpp.ops.req.StartTransactionRequest;
import org.ccg.mpp.ops.resp.AggregateResponse;
import org.ccg.mpp.ops.resp.BulkSaveResponse;
import org.ccg.mpp.ops.resp.DeleteManyResponse;
import org.ccg.mpp.ops.resp.FindByIdResponse;
import org.ccg.mpp.ops.resp.ListCollectionsResponse;
import org.ccg.mpp.ops.resp.ListProjectsResponse;
import org.ccg.mpp.ops.resp.OperationResponse;
import org.ccg.mpp.ops.resp.SaveResponse;
import org.ccg.mpp.ops.resp.SaveWhereResponse;
import org.ccg.mpp.ops.tx.SliceStateMachine;
import org.ccg.mpp.simplejs.builtins.Intrinsics;
import org.ccg.mpp.simplejs.exceptions.JsThrowException;

public final class EnforcingProjectAccess implements ProjectAccess, ScriptSession {
    private final Cache cache = IocContainer.get(Cache.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final EJson eJson = IocContainer.get(EJson.class);

    private final ScriptRequestDispatch requests;
    private UUID sessionClientId;
    private Thread sessionThread;
    private volatile boolean lastCommitFenced;

    public EnforcingProjectAccess(String username, UUID clientId) {
        this(username, clientId, null);
    }

    public EnforcingProjectAccess(String username, UUID clientId, String scopedProject) {
        this(username, clientId, scopedProject, 0);
    }

    public EnforcingProjectAccess(String username, UUID clientId, String scopedProject, int triggerDepth) {
        this.requests = new ScriptRequestDispatch(username, clientId, scopedProject, triggerDepth, this);
    }

    public long operationCount() {
        return requests.operationCount();
    }

    @Override
    public String scopedProject() {
        return requests.scopedProject();
    }

    @Override
    public boolean mayReadProcedureSource() {
        final var scopedProject = requests.scopedProject();
        return scopedProject != null && AuthorizationChecker
                .mayReadProcedureSource(cache.getAdminUserEntry(requests.username()), scopedProject);
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
        message.add("projectName", new JsonString(db));
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
        message.add("projectName", new JsonString(db));
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
        message.add("projectName", new JsonString(db));
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
    public List<String> listProjects() {
        final var scopedProject = requests.scopedProject();
        if (scopedProject != null) {
            return List.of(scopedProject);
        }
        final var response = dispatch(new ListProjectsRequest());
        if (response instanceof ListProjectsResponse listProjectsResponse) {
            return listProjectsResponse.getProjects();
        }
        throw jsError(response.getMessage());
    }

    @Override
    public void beginTransaction() {
        if (sessionClientId != null) {
            throw jsError("A transaction is already active on this script");
        }
        sessionThread = Thread.currentThread();
        final var clientId = requests.clientId();
        sessionClientId = clientId != null ? clientId : clientTracker.registerForwardedClient(requests.username());
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
        return requests.sawClusterUnavailable();
    }

    private void clearSession() {
        clearSession(false);
    }

    private void clearSession(boolean fenced) {
        if (sessionClientId != null && !fenced) {
            clientTracker.clearTransactionState(sessionClientId);
            if (!sessionClientId.equals(requests.clientId())) {
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

    @Override
    public UUID sessionClientId() {
        return sessionClientId;
    }

    @Override
    public void assertSessionThread() {
        if (sessionThread != null && sessionThread != Thread.currentThread()) {
            throw jsError("A script transaction may only be used from the thread that started it");
        }
    }

    private OperationResponse dispatch(OperationRequest request) {
        return requests.dispatch(request);
    }

    private OperationResponse dispatch(OperationRequest request, String rawJson) {
        return requests.dispatch(request, rawJson);
    }

    @Override
    public void useIntrinsics(Intrinsics value) {
        requests.useIntrinsics(value);
    }

    private JsThrowException jsError(String message) {
        return requests.jsError(message);
    }
}
